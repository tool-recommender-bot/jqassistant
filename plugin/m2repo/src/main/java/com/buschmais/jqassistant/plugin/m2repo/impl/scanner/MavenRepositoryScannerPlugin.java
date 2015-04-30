package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.core.store.api.model.Descriptor;
import com.buschmais.jqassistant.plugin.common.api.scanner.AbstractScannerPlugin;
import com.buschmais.jqassistant.plugin.common.api.scanner.filesystem.FileResource;
import com.buschmais.jqassistant.plugin.m2repo.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.api.model.RepositoryArtifactDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;
import com.buschmais.xo.api.Query.Result;
import com.buschmais.xo.api.Query.Result.CompositeRowObject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.MAVEN;
import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;

/**
 * A scanner for (remote) maven repositories.
 * 
 * @author pherklotz
 */
public class MavenRepositoryScannerPlugin extends AbstractScannerPlugin<URL, MavenRepositoryDescriptor> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenRepositoryScannerPlugin.class);

    private static final String PROPERTY_NAME_DIRECTORY = "m2repo.directory";
    private static final String PROPERTY_NAME_ARTIFACTS_KEEP = "m2repo.artifacts.keep";
    private static final String PROPERTY_NAME_FILTER_INCLUDES = "m2repo.filter.includes";
    private static final String PROPERTY_NAME_FILTER_EXCLUDES = "m2repo.filter.excludes";

    public static final String DEFAULT_M2REPO_DIR = "./jqassistant/data/m2repo";

    private File localDirectory;

    private boolean keepArtifacts = true;

    private List<String> includeFilter;
    private List<String> excludeFilter;

    /** {@inheritDoc} */
    @Override
    public boolean accepts(URL item, String path, Scope scope) throws IOException {
        return MavenScope.REPOSITORY == scope;
    }

    /**
     * Returns a string with the artifact coordinates (groupId:artifactId:[classifier:]version).
     * 
     * @param artifact
     *            the artifact
     * @param version
     *            the version to use
     * @return a string with the artifact coordinates (groupId:artifactId:[classifier:]version).
     */
    private String getCoords(Artifact artifact, String version) {
        StringBuilder builder = new StringBuilder(artifact.getGroupId()).append(":");
        builder.append(artifact.getArtifactId()).append(":");
        builder.append(artifact.getExtension()).append(":");
        if (StringUtils.isNotBlank(artifact.getClassifier())) {
            builder.append(artifact.getClassifier()).append(":");
        }
        if (version == null) {
            builder.append(artifact.getVersion());
        } else {
            builder.append(version);
        }

        return builder.toString();
    }

    /**
     * Finds or creates a repository descriptor for the given url.
     * 
     * @param store
     *            the {@link Store}
     * @param url
     *            the repository url
     * @return a {@link MavenRepositoryDescriptor} for the given url.
     */
    private MavenRepositoryDescriptor getRepositoryDescriptor(Store store, String url) {
        MavenRepositoryDescriptor repositoryDescriptor = store.find(MavenRepositoryDescriptor.class, url);
        if (repositoryDescriptor == null) {
            repositoryDescriptor = store.create(MavenRepositoryDescriptor.class);
            repositoryDescriptor.setUrl(url);
        }
        return repositoryDescriptor;
    }

    /** {@inheritDoc} */
    @Override
    public void configure() {
        localDirectory = new File(getStringProperty(PROPERTY_NAME_DIRECTORY, DEFAULT_M2REPO_DIR));
        if (getProperties().containsKey(PROPERTY_NAME_ARTIFACTS_KEEP)) {
            keepArtifacts = BooleanUtils.toBoolean(Objects.toString(getProperties().get(PROPERTY_NAME_ARTIFACTS_KEEP)));
        }
        includeFilter = getFilterPattern(PROPERTY_NAME_FILTER_INCLUDES);
        excludeFilter = getFilterPattern(PROPERTY_NAME_FILTER_EXCLUDES);
    }

    /**
     * Extracts a list of artifact filters from the given property.
     * 
     * @param propertyName
     *            The name of the property.
     * @return The list of artifact patterns.
     */
    private List<String> getFilterPattern(String propertyName) {
        String patterns = getStringProperty(propertyName, null);
        if (patterns == null) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String pattern : patterns.split(",")) {
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Migrates the descriptor to a {@link RepositoryArtifactDescriptor} and creates a relation between the descriptor and the repoDescriptor.
     * 
     * @param store
     *            the {@link Store}
     * @param repoDescriptor
     *            the containing {@link MavenRepositoryDescriptor}
     * @param lastModified
     *            timestamp of last martifact modification
     * @param descriptor
     *            the artifact descriptor
     * @return
     */
    private RepositoryArtifactDescriptor migrateToArtifactAndSetRelation(Store store, MavenRepositoryDescriptor repoDescriptor,
            long lastModified, Descriptor descriptor) {
        RepositoryArtifactDescriptor artifactDescriptor = store.addDescriptorType(descriptor, RepositoryArtifactDescriptor.class);
        artifactDescriptor.setLastModified(lastModified);
        artifactDescriptor.setContainingRepository(repoDescriptor);
        return artifactDescriptor;
    }

    /**
     * Resolves, scans and add the artifact to the {@link MavenRepositoryDescriptor}.
     * 
     * @param scanner
     *            the {@link Scanner}
     * @param repoDescriptor
     *            the {@link MavenRepositoryDescriptor}
     * @param artifactProvider
     *            the {@link ArtifactProvider}
     * @param artifactFilter
     *            The {@link ArtifactFilter}.
     * @param artifactInfo
     *            informations about the searches artifact
     * @throws IOException
     */
    private void resolveAndScan(Scanner scanner, MavenRepositoryDescriptor repoDescriptor, ArtifactProvider artifactProvider,
            ArtifactFilter artifactFilter, ArtifactInfo artifactInfo) throws IOException {
        PomModelBuilder pomModelBuilder = new PomModelBuilder(artifactProvider);
        Store store = scanner.getContext().getStore();
        String groupId = artifactInfo.getFieldValue(MAVEN.GROUP_ID);
        String artifactId = artifactInfo.getFieldValue(MAVEN.ARTIFACT_ID);
        String classifier = artifactInfo.getFieldValue(MAVEN.CLASSIFIER);
        String packaging = artifactInfo.getFieldValue(MAVEN.PACKAGING);
        String version = artifactInfo.getFieldValue(MAVEN.VERSION);
        Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, packaging, version);

        if (artifactFilter.match(RepositoryUtils.toArtifact(artifact))) {
            try {
                List<ArtifactResult> artifactResults = artifactProvider.downloadArtifact(artifact);
                for (ArtifactResult artifactResult : artifactResults) {
                    final Artifact resolvedArtifact = artifactResult.getArtifact();
                    long lastModified = artifactInfo.lastModified;
                    String coords = getCoords(resolvedArtifact, version);
                    Map<String, Object> params = new HashMap<>();
                    params.put("coords", coords);
                    params.put("lastModified", lastModified);
                    params.put("url", repoDescriptor.getUrl());
                    // skip scan if artifact is already in db
                    if (isArtifactInDb(store, params)) {
                        continue;
                    }
                    Descriptor descriptor;
                    final File artifactFile = resolvedArtifact.getFile();
                    Model model = null;
                    if ("pom".equals(resolvedArtifact.getExtension())) {
                        model = pomModelBuilder.getEffectiveModel(artifactFile);
                    }
                    if (model != null) {
                        descriptor = scanner.scan(model, artifactFile.getAbsolutePath(), null);
                    } else {
                        try (FileResource fileResource = new DefaultFileResource(artifactFile)) {
                            descriptor = scanner.scan(fileResource, artifactFile.getAbsolutePath(), null);
                        }
                    }
                    if (descriptor != null) {
                        RepositoryArtifactDescriptor artifactDescriptor =
                                migrateToArtifactAndSetRelation(store, repoDescriptor, lastModified, descriptor);
                        artifactDescriptor.setGroupId(resolvedArtifact.getGroupId());
                        artifactDescriptor.setArtifactId(resolvedArtifact.getArtifactId());
                        artifactDescriptor.setClassifier(resolvedArtifact.getClassifier());
                        artifactDescriptor.setPackaging(resolvedArtifact.getExtension());
                        artifactDescriptor.setVersion(version);

                        artifactDescriptor.setMavenCoordinates(coords);

                        Result<CompositeRowObject> queryResult =
                                store.executeQuery("MATCH (n:RepositoryArtifact)<-[:CONTAINS_ARTIFACT]-(m:Maven:Repository) "
                                        + "WHERE n.mavenCoordinates={coords} AND n.lastModified<>{lastModified} and m.url={url} RETURN n",
                                        params);
                        if (queryResult.hasResult()) {
                            RepositoryArtifactDescriptor predecessorArtifactDescriptor =
                                    queryResult.getSingleResult().get("n", RepositoryArtifactDescriptor.class);
                            artifactDescriptor.setPredecessorArtifact(predecessorArtifactDescriptor);
                            predecessorArtifactDescriptor.setContainingRepository(null);
                        }
                    } else {
                        LOGGER.debug("Could not scan artifact: " + artifactFile.getAbsoluteFile());
                    }
                    if (artifactResult != null && !keepArtifacts) {
                        artifactFile.delete();
                    }
                }
            } catch (ArtifactResolutionException e) {
                LOGGER.warn(e.getMessage());
            }
        }
    }

    /**
     * Checks whether an artifact exists already in the database or not.
     * 
     * @param store
     *            the DB store
     * @param params
     *            a Map with the parameters for a query. Parameters are:
     *            <ul>
     *            <li><b>coords</b> (= maven artifact coordinates)</li>
     *            <li><b>lastModified</b> (=last modified date of the artifact)</li>
     *            <li><b>url</b> (=the repository url)</li>
     *            </ul>
     * @return true if the artifact exists as node.
     */
    private boolean isArtifactInDb(Store store, Map<String, Object> params) {
        Result<CompositeRowObject> queryResult =
                store.executeQuery("MATCH (n:RepositoryArtifact)<-[:CONTAINS_ARTIFACT]-(m:Maven:Repository) "
                        + "WHERE n.mavenCoordinates={coords} and n.lastModified={lastModified} and m.url={url} RETURN count(n) as nodeCount;",
                        params);
        CompositeRowObject rowObject = queryResult.getSingleResult();
        Long nodeCount = rowObject.get("nodeCount", Long.class);
        return nodeCount > 0;
    }

    /** {@inheritDoc} */
    @Override
    public MavenRepositoryDescriptor scan(URL item, String path, Scope scope, Scanner scanner) throws IOException {
        String userInfo = item.getUserInfo();
        String username = StringUtils.substringBefore(userInfo, ":");
        String password = StringUtils.substringAfter(userInfo, ":");
        if (!localDirectory.exists()) {
            LOGGER.info("Creating local maven repository directory {}", localDirectory.getAbsolutePath());
            localDirectory.mkdirs();
        }
        File localRepoDir = new File(localDirectory, DigestUtils.md5Hex(item.toString()));
        // handles the remote maven index
        MavenIndex mavenIndex = new MavenIndex(item, localRepoDir, username, password);
        // used to resolve (remote) artifacts
        ArtifactProvider artifactProvider = new ArtifactProvider(item, localRepoDir, username, password);
        ArtifactFilter artifactFilter = new ArtifactFilter(includeFilter, excludeFilter);
        return scanRepository(item, scanner, mavenIndex, artifactProvider, artifactFilter);
    }

    /**
     * Scans a Repository.
     * 
     * @param item
     *            the URL
     * @param scanner
     *            the Scanner
     * @param mavenIndex
     *            the MavenIndex
     * @param artifactProvider
     *            the ArtifactResolver
     * @param artifactFilter
     *            The artifact filter to apply.
     * @return a MavenRepositoryDescriptor
     * @throws IOException
     */
    public MavenRepositoryDescriptor scanRepository(URL item, Scanner scanner, MavenIndex mavenIndex, ArtifactProvider artifactProvider,
            ArtifactFilter artifactFilter) throws IOException {

        Store store = scanner.getContext().getStore();
        // the MavenRepositoryDescriptor
        MavenRepositoryDescriptor repoDescriptor = getRepositoryDescriptor(store, item.toString());

        Date lastIndexUpdateTime = mavenIndex.getLastUpdateLocalRepo();
        Date lastScanTime = new Date(repoDescriptor.getLastScanDate());
        Date artifactsSince = lastIndexUpdateTime;
        if (lastIndexUpdateTime == null || lastIndexUpdateTime.after(lastScanTime)) {
            artifactsSince = lastScanTime;
        }

        mavenIndex.updateIndex();

        // Search artifacts
        Iterable<ArtifactInfo> searchResponse = mavenIndex.getArtifactsSince(artifactsSince);
        for (ArtifactInfo ai : searchResponse) {
            resolveAndScan(scanner, repoDescriptor, artifactProvider, artifactFilter, ai);
        }
        mavenIndex.closeCurrentIndexingContext();
        mavenIndex = null;
        repoDescriptor.setLastScanDate(System.currentTimeMillis());

        return repoDescriptor;
    }
}
