package com.dzy.cglib;

import net.sf.cglib.core.Constants;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import sun.misc.Unsafe;

import java.io.FileOutputStream;
import java.lang.reflect.Field;

public class ReturnOnStackHasElementTest {

    public static void main(String[] args) throws Throwable {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Constants.V1_8, Constants.ACC_PUBLIC, "com/dzy/cglib/ReturnOnStackHasElementTests", null, null, null);

        MethodVisitor mv = cw.visitMethod(Constants.ACC_PUBLIC, "returnOnStackHasElement", "(I)V", null, null);
        mv.visitVarInsn(Constants.ALOAD, 0);
        mv.visitVarInsn(Constants.ILOAD, 1);
        mv.visitInsn(Constants.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();

        cw.visitEnd();

        String path = System.getProperty("user.dir") + "/cglib/target/test-classes/com/dzy/cglib/";
        try (FileOutputStream fos = new FileOutputStream(path + "ReturnOnStackHasElementTests.class")) {
            fos.write(cw.toByteArray());
        }
    }
}
