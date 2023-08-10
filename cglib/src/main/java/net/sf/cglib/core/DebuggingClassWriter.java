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

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.lang.reflect.Constructor;

public class DebuggingClassWriter extends ClassVisitor {
    
    public static final String DEBUG_LOCATION_PROPERTY = "cglib.debugLocation";
    
    private static String debugLocation;
    private static Constructor traceCtor;
    
    private String className;
    private String superName;
    
    static {
        debugLocation = System.getProperty(DEBUG_LOCATION_PROPERTY);
        if (debugLocation != null) {
            System.err.println("CGLIB debugging enabled, writing to '" + debugLocation + "'");
            try {
              Class clazz = Class.forName("org.objectweb.asm.util.TraceClassVisitor");
              traceCtor = clazz.getConstructor(new Class[]{ClassVisitor.class, PrintWriter.class});
            } catch (Throwable ignore) {
            }
        }
    }
    
    public DebuggingClassWriter(int flags) {
	super(Constants.ASM_API,
            // 创建了一个ClassWriter作为cv持有
            new ClassWriter(flags));
    }

    public void visit(int version,
                      int access,
                      String name,
                      String signature,
                      String superName,
                      String[] interfaces) {
        // 将传入的name的/替换为.，然后赋值给自身的className
        className = name.replace('/', '.');
        // 将传入的superName的/替换为.，然后赋值给自身的superName
        this.superName = superName.replace('/', '.');
        super.visit(version, access, name, signature, superName, interfaces);
    }
    
    public String getClassName() {
        return className;
    }
    
    public String getSuperName() {
        return superName;
    }
    
    public byte[] toByteArray() {
        
      return (byte[]) java.security.AccessController.doPrivileged(
        new java.security.PrivilegedAction() {
            public Object run() {
                
                // 调用持有的ClassVisitor的toByteArray方法，默认持有的cv是ClassWriter
                byte[] b = ((ClassWriter) DebuggingClassWriter.super.cv).toByteArray();
                // 如果debugLocation不为null的话
                if (debugLocation != null) {
                    // 将持有的className的.转换为文件分隔符，得到路径
                    String dirs = className.replace('.', File.separatorChar);
                    try {
                        // 然后使用debugLocation + 文件分隔符 + dirs构成的路径的父路径作为目录路径，然后创建目录
                        new File(debugLocation + File.separatorChar + dirs).getParentFile().mkdirs();

                        // 在dirs后拼接.class作为child路径 以debugLocation作为parent路径，然后生成一个文件对象
                        File file = new File(new File(debugLocation), dirs + ".class");
                        // 将class文件的字节码输出到对应的文件中
                        OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
                        try {
                            out.write(b);
                        } finally {
                            out.close();
                        }

                        // 如果traceClassVisitor的构造器不为null
                        if (traceCtor != null) {
                            // 在dirs后面拼接.asm作为child路径 以debugLocation作为parent路径，生成一个文件对象
                            file = new File(new File(debugLocation), dirs + ".asm");
                            // 打开文件流
                            out = new BufferedOutputStream(new FileOutputStream(file));
                            try {
                                // 将class文件的二进制封装成classReader
                                ClassReader cr = new ClassReader(b);
                                // 将输出流包装成PrintWriter
                                PrintWriter pw = new PrintWriter(new OutputStreamWriter(out));
                                // 使用PrintWriter初始化TraceClassVisitor
                                ClassVisitor tcv = (ClassVisitor)traceCtor.newInstance(new Object[]{null, pw});
                                // 然后访问classReader，将内容输出到asm文件中
                                cr.accept(tcv, 0);
                                pw.flush();
                            } finally {
                                out.close();
                            }
                        }
                    } catch (Exception e) {
                        throw new CodeGenerationException(e);
                    }
                }
                return b;
             }  
            });
            
        }
    }
