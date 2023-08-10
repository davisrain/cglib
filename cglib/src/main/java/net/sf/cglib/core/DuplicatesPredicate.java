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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class DuplicatesPredicate implements Predicate {
  private final Set unique;
  private final Set rejected;

  /**
   * Constructs a DuplicatesPredicate that will allow subclass bridge methods to be preferred over
   * superclass non-bridge methods.
   */
  public DuplicatesPredicate() {
    unique = new HashSet();
    rejected = Collections.emptySet();
  }

  /**
   * Constructs a DuplicatesPredicate that prefers using superclass non-bridge methods despite a
   * subclass method with the same signtaure existing (if the subclass is a bridge method).
   */
  public DuplicatesPredicate(List allMethods) {
    rejected = new HashSet();
    unique = new HashSet();

    // Traverse through the methods and capture ones that are bridge
    // methods when a subsequent method (from a non-interface superclass)
    // has the same signature but isn't a bridge. Record these so that
    // we avoid using them when filtering duplicates.
    Map scanned = new HashMap();
    Map suspects = new HashMap();
    // 遍历传入的方法集合
    for (Object o : allMethods) {
      Method method = (Method) o;
      // 创建一个MethodWrapperKey，里面持有了方法的名称，参数的类名集合以及返回值的类名，相当于是方法的签名
      Object sig = MethodWrapper.create(method);
      // 查看map中是否存在对应的MethodWrapperKey
      Method existing = (Method) scanned.get(sig);
      // 如果不存在，将其放入scanned集合中
      if (existing == null) {
        scanned.put(sig, method);
      }
      // 如果scanned这个map中已经存在对应的方法签名
      // 并且 suspects这个map中不存在这个方法签名 并且 已存在的方法是桥接的 并且 该方法不是桥接的，那么将已经存在的签名放入suspects中
      else if (!suspects.containsKey(sig) && existing.isBridge() && !method.isBridge()) {
        // TODO: this currently only will capture a single bridge. it will not work
        // if there's Child.bridge1 Middle.bridge2 Parent.concrete.  (we'd offer the 2nd bridge).
        // no idea if that's even possible tho...
        suspects.put(sig, existing);
      }
    }

    // 如果suspects不为空
    if (!suspects.isEmpty()) {
      Set classes = new HashSet();
      // 使用rejected为参数创建一个UnnecessaryBridgeFinder
      UnnecessaryBridgeFinder finder = new UnnecessaryBridgeFinder(rejected);
      // 遍历suspects的values
      for (Object o : suspects.values()) {
        Method m = (Method) o;
        // 将方法的声明类添加进classes集合中
        classes.add(m.getDeclaringClass());
        // 然后向finder中添加对应的suspectMethod
        finder.addSuspectMethod(m);
      }
      // 遍历classes集合
      for (Object o : classes) {
        Class c = (Class) o;
        try {
          // 获取classloader
          ClassLoader cl = getClassLoader(c);
          if (cl == null) {
            continue;
          }
          // 加载class对应的class文件
          InputStream is = cl.getResourceAsStream(c.getName().replace('.', '/') + ".class");
          if (is == null) {
            continue;
          }
          try {
            // 然后生成一个classReader，使用finder去访问它
            new ClassReader(is).accept(finder, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
          } finally {
            is.close();
          }
        } catch (IOException ignored) {
        }
      }
    }
  }

  public boolean evaluate(Object arg) {
    // 如果rejected集合中不包含这个方法 并且 将方法转换为MethodWrapperKey之后能够添加进unique集合中，满足条件，才不会被过滤掉
    return !rejected.contains(arg) && unique.add(MethodWrapper.create((Method) arg));
  }
  
  private static ClassLoader getClassLoader(Class c) {
    ClassLoader cl = c.getClassLoader();
    if (cl == null) {
      cl = DuplicatesPredicate.class.getClassLoader();
    }
    if (cl == null) {
      cl = Thread.currentThread().getContextClassLoader();
    }
    return cl;
  }

  private static class UnnecessaryBridgeFinder extends ClassVisitor {
    private final Set rejected;

    private Signature currentMethodSig = null;
    private Map methods = new HashMap();

    UnnecessaryBridgeFinder(Set rejected) {
      super(Constants.ASM_API);
      this.rejected = rejected;
    }

    void addSuspectMethod(Method m) {
      // 以方法的Signature为key，放入methods这个map中
      methods.put(ReflectUtils.getSignature(m), m);
    }

    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {}

    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      Signature sig = new Signature(name, desc);
      // 通过Signature从methods这个map中找到对应的方法
      final Method currentMethod = (Method) methods.remove(sig);
      // 如果方法存在
      if (currentMethod != null) {
        currentMethodSig = sig;
        return new MethodVisitor(Constants.ASM_API) {
          public void visitMethodInsn(
              int opcode, String owner, String name, String desc, boolean itf) {
            // 如果发现字节码是invokespecial 并且 currentMethodSig不为null
            if (opcode == Opcodes.INVOKESPECIAL && currentMethodSig != null) {
              // 根据调用的name和desc生成一个新的Signature
              Signature target = new Signature(name, desc);
              // 如果新的signature等于currentMethodSig，那么将当前currentMethod添加进拒绝集合中。
              // 说明这是修改访问范围的桥接方法类型，需要将桥接方法加入到拒绝集合里面，将其过滤掉
              if (target.equals(currentMethodSig)) {
                rejected.add(currentMethod);
              }
              // 将currentMethodSig置为null
              currentMethodSig = null;
            }
          }
        };
      } else {
        return null;
      }
    }
  }
}
