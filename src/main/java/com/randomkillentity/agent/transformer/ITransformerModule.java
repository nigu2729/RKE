package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public interface ITransformerModule {
    boolean matchesClass(String className);
    boolean matchesMethod(String className, MethodNode method);
    boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method);
    default int getPriority() { return 1000; }
}