package com.buschmais.jqassistant.scanner.test;

import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.buschmais.jqassistant.scanner.ClassScanner;
import com.buschmais.jqassistant.store.api.Store;
import com.buschmais.jqassistant.store.api.model.ClassDescriptor;
import com.buschmais.jqassistant.store.api.model.PackageDescriptor;

@RunWith(MockitoJUnitRunner.class)
public abstract class AbstractScannerTest {

	@Mock
	protected Store store;

	protected ClassScanner scanner;

	private final Map<String, PackageDescriptor> packageCache = new HashMap<String, PackageDescriptor>();

	protected ClassDescriptor javaLangObject;
	protected ClassDescriptor _void;

	@Before
	public void createScanner() {
		scanner = new ClassScanner(store);
		this.javaLangObject = stubClass(Object.class);
		this._void = stubClass("void");
	}

	protected PackageDescriptor stubPackage(String fullQualifiedName) {
		PackageDescriptor packageDescriptor = packageCache
				.get(fullQualifiedName);
		if (packageDescriptor == null) {
			int i = fullQualifiedName.lastIndexOf('.');
			PackageDescriptor parentDescriptor = null;
			String name;
			if (i != -1) {
				String parentName = fullQualifiedName.substring(0, i);
				name = fullQualifiedName.substring(i + 1,
						fullQualifiedName.length());
				parentDescriptor = stubPackage(parentName);
			} else {
				name = fullQualifiedName;
			}
			packageDescriptor = new PackageDescriptor();
			when(store.resolvePackageDescriptor(parentDescriptor, name))
					.thenReturn(packageDescriptor);
			packageCache.put(fullQualifiedName, packageDescriptor);
		}
		return packageDescriptor;
	}

	protected ClassDescriptor stubClass(Class<?> c) {
		return stubClass(stubPackage(c.getPackage().getName()),
				c.getSimpleName());
	}

	protected ClassDescriptor stubClass(String className) {
		ClassDescriptor classDescriptor = new ClassDescriptor();
		when(store.resolveClassDescriptor(null, className)).thenReturn(
				classDescriptor);
		return classDescriptor;
	}

	protected ClassDescriptor stubClass(PackageDescriptor packageDescriptor,
			String className) {
		ClassDescriptor classDescriptor = new ClassDescriptor();
		when(store.resolveClassDescriptor(packageDescriptor, className))
				.thenReturn(classDescriptor);
		return classDescriptor;
	}
}