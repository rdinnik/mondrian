/*
//This software is subject to the terms of the Common Public License
//Agreement, available at the following URL:
//http://www.opensource.org/licenses/cpl.html.
//Copyright (C) 2004-2005 TONBELLER AG
//All Rights Reserved.
//You must accept the terms of that agreement to use this software.
 */
package mondrian.rolap.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * a SmartCache implementation that uses hard references. Used for testing
 */
public class HardSmartCache implements SmartCache {
    Map cache = new HashMap();
    
    public Object put(Object key, Object value) {
        return cache.put(key, value);
    }

    public Object get(Object key) {
        return cache.get(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

}