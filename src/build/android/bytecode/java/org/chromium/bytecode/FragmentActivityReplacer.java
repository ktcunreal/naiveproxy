// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.bytecode;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.File;
import java.io.IOException;

/**
 * Java application that modifies Fragment.getActivity() to return an Activity instead of a
 * FragmentActivity, and updates any existing getActivity() calls to reference the updated method.
 *
 * See crbug.com/1144345 for more context.
 */
public class FragmentActivityReplacer extends ByteCodeRewriter {
    private static final String FRAGMENT_CLASS_PATH = "androidx/fragment/app/Fragment.class";
    private static final String FRAGMENT_ACTIVITY_INTERNAL_CLASS_NAME =
            "androidx/fragment/app/FragmentActivity";
    private static final String ACTIVITY_INTERNAL_CLASS_NAME = "android/app/Activity";
    private static final String GET_ACTIVITY_METHOD_NAME = "getActivity";
    private static final String REQUIRE_ACTIVITY_METHOD_NAME = "requireActivity";
    private static final String OLD_METHOD_DESCRIPTOR =
            "()Landroidx/fragment/app/FragmentActivity;";
    private static final String NEW_METHOD_DESCRIPTOR = "()Landroid/app/Activity;";

    public static void main(String[] args) throws IOException {
        // Invoke this script using //build/android/gyp/bytecode_processor.py
        if (args.length != 2) {
            System.err.println("Expected 2 arguments: [input.jar] [output.jar]");
            System.exit(1);
        }

        FragmentActivityReplacer rewriter = new FragmentActivityReplacer();
        rewriter.rewrite(new File(args[0]), new File(args[1]));
    }

    @Override
    protected boolean shouldRewriteClass(String classPath) {
        return true;
    }

    @Override
    protected ClassVisitor getClassVisitorForClass(String classPath, ClassVisitor delegate) {
        ClassVisitor getActivityReplacer = new GetActivityReplacer(delegate);
        if (classPath.equals(FRAGMENT_CLASS_PATH)) {
            return new FragmentClassVisitor(getActivityReplacer);
        }
        return getActivityReplacer;
    }

    /** Updates any Fragment.getActivity/requireActivity() calls to call the replaced method. */
    private static class GetActivityReplacer extends ClassVisitor {
        private GetActivityReplacer(ClassVisitor baseVisitor) {
            super(Opcodes.ASM7, baseVisitor);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM7, base) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name,
                        String descriptor, boolean isInterface) {
                    if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL)
                            && descriptor.equals(OLD_METHOD_DESCRIPTOR)
                            && (name.equals(GET_ACTIVITY_METHOD_NAME)
                                    || name.equals(REQUIRE_ACTIVITY_METHOD_NAME))) {
                        super.visitMethodInsn(
                                opcode, owner, name, NEW_METHOD_DESCRIPTOR, isInterface);
                    } else {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                }
            };
        }
    }

    /**
     * Makes Fragment.getActivity() and Fragment.requireActivity() non-final, and changes their
     * return types to Activity.
     */
    private static class FragmentClassVisitor extends ClassVisitor {
        private FragmentClassVisitor(ClassVisitor baseVisitor) {
            super(Opcodes.ASM7, baseVisitor);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor base;
            // Update the descriptor of getActivity/requireActivity, and make them non-final.
            if (name.equals(GET_ACTIVITY_METHOD_NAME)
                    || name.equals(REQUIRE_ACTIVITY_METHOD_NAME)) {
                base = super.visitMethod(
                        access & ~Opcodes.ACC_FINAL, name, NEW_METHOD_DESCRIPTOR, null, exceptions);
            } else {
                base = super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            return new MethodRemapper(base, new Remapper() {
                @Override
                public String mapType(String internalName) {
                    if (internalName.equals(FRAGMENT_ACTIVITY_INTERNAL_CLASS_NAME)) {
                        return ACTIVITY_INTERNAL_CLASS_NAME;
                    }
                    return internalName;
                }
            });
        }
    }
}
