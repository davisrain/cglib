/*
 * Copyright 2003 The Apache Software Foundation
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

import org.objectweb.asm.ClassWriter;

public class DefaultGeneratorStrategy implements GeneratorStrategy {
    public static final DefaultGeneratorStrategy INSTANCE = new DefaultGeneratorStrategy();
    
    public byte[] generate(ClassGenerator cg) throws Exception {
        // 获取classVisitor，默认实现是生成一个DebuggingClassWriter
        DebuggingClassWriter cw = getClassVisitor();
        // 调用transform方法先对classGenerator进行转换，默认是直接返回cg；
        // 然后调用cg的generateClass方法，并且将classWriter传入
        transform(cg).generateClass(cw);
        // 然后调用transform方法对classWriter持有的字节数组进行转换，默认是直接返回该字节数组
        return transform(cw.toByteArray());
    }

    protected DebuggingClassWriter getClassVisitor() throws Exception {
      return new DebuggingClassWriter(ClassWriter.COMPUTE_FRAMES);
    }

    protected final ClassWriter getClassWriter() {
	// Cause compile / runtime errors for people who implemented the old
	// interface without using @Override
	throw new UnsupportedOperationException("You are calling " +
		"getClassWriter, which no longer exists in this cglib version.");
    }
    
    protected byte[] transform(byte[] b) throws Exception {
        return b;
    }

    protected ClassGenerator transform(ClassGenerator cg) throws Exception {
        return cg;
    }
}
