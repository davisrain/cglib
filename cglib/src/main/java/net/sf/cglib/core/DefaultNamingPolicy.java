/*
 * Copyright 2003,2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.cglib.core;

import java.util.Set;

/**
 * The default policy used by {@link AbstractClassGenerator}.
 * Generates names such as
 * <p><code>net.sf.cglib.Foo$$EnhancerByCGLIB$$38272841</code><p>
 * This is composed of a prefix based on the name of the superclass, a fixed
 * string incorporating the CGLIB class responsible for generation, and a
 * hashcode derived from the parameters used to create the object. If the same
 * name has been previously been used in the same <code>ClassLoader</code>, a
 * suffix is added to ensure uniqueness.
 */
public class DefaultNamingPolicy implements NamingPolicy {
    public static final DefaultNamingPolicy INSTANCE = new DefaultNamingPolicy();

    /**
     * This allows to test collisions of {@code key.hashCode()}.
     */
    private final static boolean STRESS_HASH_CODE = Boolean.getBoolean("net.sf.cglib.test.stressHashCodes");
    
    public String getClassName(String prefix, String source, Object key, Predicate names) {
        // 如果前缀为null的话，使用net.sf.cglib.empty.Object
        if (prefix == null) {
            prefix = "net.sf.cglib.empty.Object";
        }
        // 如果前缀是以java开头的，在前缀前面加上$符号
        else if (prefix.startsWith("java")) {
            prefix = "$" + prefix;
        }
        // 基础名称 = 前缀 + $$ + source的最后一个.后面的内容 + tag(默认是ByCGLIB) + $$ + 根据是否要添加hashcode来决定是0还是key的hashcode
        // 以EnhanceKey为例，base = net.sf.cglib.proxy.Enhancer$EnhancerKey$$KeyFactoryByCGLIB$$hashcode
        String base =
            prefix + "$$" + 
            source.substring(source.lastIndexOf('.') + 1) +
            getTag() + "$$" +
            Integer.toHexString(STRESS_HASH_CODE ? 0 : key.hashCode());
        String attempt = base;
        int index = 2;
        // 如果predicate的evaluate为true的话，说明不符合条件，在attempt的后面添加_index，直到满足条件为止
        while (names.evaluate(attempt))
            attempt = base + "_" + index++;
        // 返回类名
        return attempt;
    }

    /**
     * Returns a string which is incorporated into every generated class name.
     * By default returns "ByCGLIB"
     */
    protected String getTag() {
        return "ByCGLIB";
    }

  public int hashCode() {
    return getTag().hashCode();
  }

  public boolean equals(Object o) {
    return (o instanceof DefaultNamingPolicy) && ((DefaultNamingPolicy) o).getTag().equals(getTag());
  }
}
