/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2004-2005 TONBELLER AG
// Copyright (C) 2006-2009 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.rolap.cache;

import java.util.Map;

/**
 * Defines a cache API. Implementations exist for hard and soft references.
 *
 * <p>This interface implements the {@link Iterable}. The {@link #iterator()}
 * method returns an iterator over all entries in the cache. The iterator
 * is mutable.
 *
 * @author av
 * @since Nov 21, 2005
 * @version $Id$
 */
public interface SmartCache <K, V> extends Iterable<Map.Entry<K, V>> {
    /**
     * Places a key/value pair into the queue.
     *
     * @param key Key
     * @param value Value
     * @return the previous value of <code>key</code> or null
     */
    V put(K key, V value);

    V get(K key);

    void clear();

    int size();
}

// End SmartCache.java
