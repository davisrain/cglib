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

import net.sf.cglib.transform.ClassTransformer;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Juozas Baliuka, Chris Nokleberg
 */
public class ClassEmitter extends ClassTransformer {
    private ClassInfo classInfo;
    private Map fieldInfo;

    private static int hookCounter;
    private MethodVisitor rawStaticInit;
    private CodeEmitter staticInit;
    private CodeEmitter staticHook;
    private Signature staticHookSig;

    public ClassEmitter(ClassVisitor cv) {
        setTarget(cv);
    }

    public ClassEmitter() {
        super(Constants.ASM_API);
    }

    public void setTarget(ClassVisitor cv) {
        // 将cv设置为自身的持有的cv
        this.cv = cv;
        fieldInfo = new HashMap();

        // just to be safe
        staticInit = staticHook = null;
        staticHookSig = null;
    }

    synchronized private static int getNextHook() {
        return ++hookCounter;
    }

    public ClassInfo getClassInfo() {
        return classInfo;
    }

    public void begin_class(int version, final int access, String className, final Type superType, final Type[] interfaces, String source) {
        // 将类名也转换为描述符，并且转换成Type
        final Type classType = Type.getType("L" + className.replace('.', '/') + ";");
        // 创建一个classInfo的实现类赋值给自身的classInfo属性
        classInfo = new ClassInfo() {
            public Type getType() {
                return classType;
            }
            public Type getSuperType() {
                return (superType != null) ? superType : Constants.TYPE_OBJECT;
            }
            public Type[] getInterfaces() {
                return interfaces;
            }
            public int getModifiers() {
                return access;
            }
        };
        // 调用原本持有的classVisitor的visit方法，一般情况下持有的visitor都是DebuggingClassWriter类型的
        cv.visit(version,
                 access,
                 // 获取internalName，即不包括L和;符号
                 classInfo.getType().getInternalName(),
                 null,
                 classInfo.getSuperType().getInternalName(),
                 TypeUtils.toInternalNames(interfaces));
        // 如果source不为null的话，一般情况下source为<generated>，表示是class文件是动态生成的，不是由sourceFile编译来的
        if (source != null)
            cv.visitSource(source, null);
        // 调用init方法进行初始化，模板方法，留给子类实现
        init();
    }

    public CodeEmitter getStaticHook() {
        // 如果类是接口类型的，报错
         if (TypeUtils.isInterface(getAccess())) {
             throw new IllegalStateException("static hook is invalid for this class");
         }
         // 如果staticHook为null
         if (staticHook == null) {
             // 创建一个Signature，方法名为CGLIB$STATICHOOK+hookCount，描述符为()V
             staticHookSig = new Signature("CGLIB$STATICHOOK" + getNextHook(), "()V");
             // 调用begin_method，调用cv的VisitMethod获取到一个MethodVisitor
             staticHook = begin_method(Constants.ACC_STATIC,
                                       staticHookSig,
                                       null);
             // 如果staticInit不为null的话
             if (staticInit != null) {
                 // 调用staticInit的invoke_static_this方法，将staticHookSig作为参数
                 // 作用是在<clinit>方法中插入invokestatic的字节码，用于调用CGLIB$STATICHOOK方法
                 staticInit.invoke_static_this(staticHookSig);
             }
         }
         // 返回staticHook
         return staticHook;
    }

    protected void init() {
    }

    public int getAccess() {
        return classInfo.getModifiers();
    }

    public Type getClassType() {
        return classInfo.getType();
    }

    public Type getSuperType() {
        return classInfo.getSuperType();
    }

    public void end_class() {
        // 如果staticHook存在，但是staticInit不存在
        if (staticHook != null && staticInit == null) {
            // force creation of static init
            // 那么强行创建<clinit>方法，在其code里添加invokestatic staticHook方法对应的常量池索引这句字节码
            begin_static();
        }
        // 如果staticInit不为null
        if (staticInit != null) {
            // 向staticHook方法中添加return字节码
            staticHook.return_value();
            // 计算staticHook的maxLocals maxStack stackMapFrame
            staticHook.end_method();
            // 调用rawStaticInit的visitInsn方法，向code中插入return字节码
            rawStaticInit.visitInsn(Constants.RETURN);
            // 计算<clinit>方法的maxLocals maxStack stackMapFrame
            rawStaticInit.visitMaxs(0, 0);
            // 然后将staticInit staticHook staticHookSig都设置为null
            staticInit = staticHook = null;
            staticHookSig = null;
        }
        // 调用持有的classVisitor的visitEnd方法，如果最终被装饰的类是ClassWriter，该方法不会做任何操作
        cv.visitEnd();
    }

    public CodeEmitter begin_method(int access, Signature sig, Type[] exceptions) {
        if (classInfo == null)
            throw new IllegalStateException("classInfo is null! " + this);
        // 调用cv的visitMethod方法，生成一个MethodVisitor
        MethodVisitor v = cv.visitMethod(access,
                                         sig.getName(),
                                         sig.getDescriptor(),
                                         null,
                                         TypeUtils.toInternalNames(exceptions));
        // 如果签名void <clinit>()，即方法是类构造器 并且 类是访问描述符表示该类不是接口类型的
        if (sig.equals(Constants.SIG_STATIC) && !TypeUtils.isInterface(getAccess())) {
            // 那么将rawStaticInit赋值为刚才生成的methodVisitor，表示静态初始化方法为该mv
            rawStaticInit = v;
            // 将methodVisitor包装一下
            MethodVisitor wrapped = new MethodVisitor(Constants.ASM_API, v) {
                public void visitMaxs(int maxStack, int maxLocals) {
                    // ignore
                }
                public void visitInsn(int insn) {
                    if (insn != Constants.RETURN) {
                        super.visitInsn(insn);
                    }
                }
            };
            // 根据ClassEmitter wrappedMethodVisitor access signature exceptions生成一个CodeEmitter赋值给staticInit
            staticInit = new CodeEmitter(this, wrapped, access, sig, exceptions);
            // 如果静态钩子为null
            if (staticHook == null) {
                // force static hook creation
                // 获取静态钩子
                getStaticHook();
            }
            // 如果不为null，调用staticInit的invoke_static_this，将staticHookSig传入
            // 作用是在<clinit>方法的code中添加invokestatic CGLIB$STATICHOOK这个方法的常量池索引这句字节码
            else {
                staticInit.invoke_static_this(staticHookSig);
            }
            // 返回staticInit
            return staticInit;
        }
        // 如果签名等于staticHook的签名，初始化一个CodeEmitter返回，该实例的isStaticHook返回true
        else if (sig.equals(staticHookSig)) {
            return new CodeEmitter(this, v, access, sig, exceptions) {
                public boolean isStaticHook() {
                    return true;
                }
            };
        }
        // 如果是其他方法，创建一个CodeEmitter返回
        else {
            return new CodeEmitter(this, v, access, sig, exceptions);
        }
    }

    public CodeEmitter begin_static() {
        return begin_method(Constants.ACC_STATIC, Constants.SIG_STATIC, null);
    }

    public void declare_field(int access, String name, Type type, Object value) {
        // 根据name从持有的fieldInfo中获取存在的FieldInfo
        FieldInfo existing = (FieldInfo)fieldInfo.get(name);
        // 根据access name type 和value创建一个FieldInfo
        FieldInfo info = new FieldInfo(access, name, type, value);
        // 如果有已存在的fieldInfo
        if (existing != null) {
            // 那么判断新建的和已存在的是否不相等，如果不相等，报错
            if (!info.equals(existing)) {
                throw new IllegalArgumentException("Field \"" + name + "\" has been declared differently");
            }
        }
        // 如果没有已存在的fieldInfo
        else {
            // 根据name将新建的info放入map中
            fieldInfo.put(name, info);
            // 然后调用自身持有的classVisitor的visitField方法，将type转换为描述符
            // 具体实现是创建一个FieldWriter并添加进ClassWriter的fieldWriter链表中
            cv.visitField(access, name, type.getDescriptor(), null, value);
        }
    }

    // TODO: make public?
    boolean isFieldDeclared(String name) {
        return fieldInfo.get(name) != null;
    }

    FieldInfo getFieldInfo(String name) {
        FieldInfo field = (FieldInfo)fieldInfo.get(name);
        if (field == null) {
            throw new IllegalArgumentException("Field " + name + " is not declared in " + getClassType().getClassName());
        }
        return field;
    }
    
    static class FieldInfo {
        int access;
        String name;
        Type type;
        Object value;
        
        public FieldInfo(int access, String name, Type type, Object value) {
            this.access = access;
            this.name = name;
            this.type = type;
            this.value = value;
        }

        public boolean equals(Object o) {
            if (o == null)
                return false;
            if (!(o instanceof FieldInfo))
                return false;
            FieldInfo other = (FieldInfo)o;
            if (access != other.access ||
                !name.equals(other.name) ||
                !type.equals(other.type)) {
                return false;
            }
            if ((value == null) ^ (other.value == null))
                return false;
            if (value != null && !value.equals(other.value))
                return false;
            return true;
        }

        public int hashCode() {
            return access ^ name.hashCode() ^ type.hashCode() ^ ((value == null) ? 0 : value.hashCode());
        }
    }

    public void visit(int version,
                      int access,
                      String name,
                      String signature,
                      String superName,
                      String[] interfaces) {
        begin_class(version,
                    access,
                    name.replace('/', '.'),
                    TypeUtils.fromInternalName(superName),
                    TypeUtils.fromInternalNames(interfaces),
                    null); // TODO
    }
    
    public void visitEnd() {
        end_class();
    }
    
    public FieldVisitor visitField(int access,
                                   String name,
                                   String desc,
                                   String signature,
                                   Object value) {
        declare_field(access, name, Type.getType(desc), value);
        return null; // TODO
    }
    
    public MethodVisitor visitMethod(int access,
                                     String name,
                                     String desc,
                                     String signature,
                                     String[] exceptions) {
        return begin_method(access,
                            new Signature(name, desc),
                            TypeUtils.fromInternalNames(exceptions));        
    }
}
