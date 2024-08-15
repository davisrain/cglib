package com.dzy.cglib;

import net.sf.cglib.core.Signature;
import net.sf.cglib.reflect.FastClass;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

public class FastClassTest {


    public static void main(String[] args) throws Throwable {
        Foo foo = new Foo();

        long count = 10L * 10000 * 10000;
        // 1.reflection
        Method incrementMethod = Foo.class.getDeclaredMethod("increment");
        long start = System.currentTimeMillis();
        for (long i = 0; i < count; i++) {
            incrementMethod.invoke(foo);
        }
        foo.clear();
        long end = System.currentTimeMillis();
        System.out.println("reflection method invoke use " + (end - start) + " mills");

        // 2.MethodHandle
        MethodType methodType = MethodType.methodType(void.class);
        MethodHandle incrementMethodHandle = MethodHandles.lookup().findVirtual(Foo.class, "increment",methodType);
        incrementMethodHandle.bindTo(foo);

        start = System.currentTimeMillis();
        for (long i = 0; i < count; i++) {
            incrementMethodHandle.invoke(foo);
        }
        end = System.currentTimeMillis();
        System.out.println("method handle invoke use " + (end - start) + " mills");

        // 3.FastClass
        FastClass.Generator generator = new FastClass.Generator();
        generator.setType(Foo.class);
        FastClass fastFoo = generator.create();
        int incrementMethodIndex = fastFoo.getIndex(new Signature("increment", "()V"));

        start = System.currentTimeMillis();
        for (long i = 0; i < count; i++) {
            fastFoo.invoke(incrementMethodIndex, foo, new Object[]{"if invoked method's params num is 0, this param can be anything"});
        }
        foo.clear();
        end = System.currentTimeMillis();
        System.out.println("fast class method invoke use " + (end - start) + " mills");

        // 4.normal
        start = System.currentTimeMillis();
        for (long i = 0; i < count; i++) {
            foo.increment();
        }
        foo.clear();
        end = System.currentTimeMillis();
        System.out.println("normal method invoke use " + (end - start) + " mills");
    }


    static class Foo {

        int i;

        public void increment() {
            i++;
        }

        public void clear() {
            i = 0;
        }
    }
}
