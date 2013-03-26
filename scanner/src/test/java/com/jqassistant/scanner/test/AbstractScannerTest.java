package com.jqassistant.scanner.test;

import org.junit.After;
import org.junit.Before;

import com.buschmais.jqassistant.scanner.DependencyScanner;
import com.buschmais.jqassistant.store.api.Store;
import com.buschmais.jqassistant.store.impl.EmbeddedGraphStore;

public abstract class AbstractScannerTest {

	private Store store;
	protected DependencyScanner scanner;

	@Before
	public void startStore() {
		store = new EmbeddedGraphStore("target/graphdb");
		scanner = new DependencyScanner(store);
		store.start();
	}

	@After
	public void stopStore() {
		store.stop();
	}

}
