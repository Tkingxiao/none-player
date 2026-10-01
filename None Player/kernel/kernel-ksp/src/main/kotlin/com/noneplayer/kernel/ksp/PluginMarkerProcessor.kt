package com.noneplayer.kernel.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/**
 * `@PluginMarker` 处理器：编译期发现插件并生成 [PluginIndex] 贡献类。
 *
 * 对每个标注 `@PluginMarker` 的类：
 *  1. 校验它是具体类且实现了 `com.noneplayer.kernel.api.Plugin`；
 *  2. 校验存在可访问的无参构造（生成代码将直接 `XxxPlugin()` 实例化）；
 *  3. 生成 `com.noneplayer.kernel.generated.<SimpleName>PluginIndex`。
 *
 * 全部为编译期产物，零反射，不受 R8 混淆影响（设计文档 §3.3 方案 B）。
 */
class PluginMarkerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: com.google.devtools.ksp.processing.KSPLogger,
) : SymbolProcessor {

    private companion object {
        const val ANNOTATION_FQN = "com.noneplayer.kernel.api.PluginMarker"
        const val PLUGIN_FQN = "com.noneplayer.kernel.api.Plugin"
        const val PLUGIN_INDEX_FQN = "com.noneplayer.kernel.api.PluginIndex"
        const val GENERATED_PACKAGE = "com.noneplayer.kernel.generated"
    }

    private var processed = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(ANNOTATION_FQN)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val deferred = mutableListOf<KSAnnotated>()

        for (clazz in symbols) {
            val fqn = clazz.qualifiedName?.asString() ?: continue
            if (fqn in processed) continue

            if (!clazz.validate()) {
                // 所在文件尚有编译错误，推迟到下一轮
                deferred += clazz
                continue
            }

            if (!validatePluginClass(clazz)) {
                // E71 修复：此前校验失败的类不会加入 processed，KSP 多轮增量编译期间
                // （该注解符号持续可见时）会被反复重新校验、反复重复打印同一条错误日志。
                // 校验结果是确定性的（基于类声明本身，不依赖尚未解析的符号），
                // 失败一次即可认定"本轮已处理"，不需要重试。
                processed += fqn
                continue
            }

            generateIndex(clazz, fqn)
            processed += fqn
        }
        return deferred
    }

    private fun validatePluginClass(clazz: KSClassDeclaration): Boolean {
        val name = clazz.qualifiedName?.asString() ?: clazz.simpleName.asString()

        if (clazz.classKind != ClassKind.CLASS || clazz.modifiers.contains(Modifier.ABSTRACT)) {
            logger.error("@PluginMarker 只能标注在具体类上（不能是接口、object 或抽象类）", clazz)
            return false
        }
        if (clazz.modifiers.contains(Modifier.DATA)) {
            logger.error("@PluginMarker 不能标注在 data class 上", clazz)
            return false
        }
        if (!implementsPlugin(clazz)) {
            logger.error("@PluginMarker 类必须实现 $PLUGIN_FQN", clazz)
            return false
        }

        val ctor = clazz.primaryConstructor
        if (ctor == null || ctor.parameters.isNotEmpty()) {
            logger.error("@PluginMarker 类必须有无参构造函数（插件由内核无参实例化）", clazz)
            return false
        }
        if (ctor.modifiers.any { it == Modifier.PRIVATE || it == Modifier.PROTECTED || it == Modifier.INTERNAL }) {
            logger.error("@PluginMarker 类的构造函数必须可公开访问", clazz)
            return false
        }
        return true
    }

    /** 沿超类型链（含 classpath 上的已编译类型）查找 [PLUGIN_FQN] */
    private fun implementsPlugin(clazz: KSClassDeclaration, depth: Int = 0): Boolean {
        if (depth > 16) return false
        for (superType in clazz.superTypes) {
            val declaration = superType.resolve().declaration as? KSClassDeclaration ?: continue
            when (declaration.qualifiedName?.asString()) {
                PLUGIN_FQN -> return true
                "kotlin.Any" -> Unit
                else -> if (implementsPlugin(declaration, depth + 1)) return true
            }
        }
        return false
    }

    private fun generateIndex(clazz: KSClassDeclaration, pluginFqn: String) {
        val simpleName = clazz.simpleName.asString()
        // LibraryPlugin -> LibraryPluginIndex（避免 LibraryPluginPluginIndex）
        val base = simpleName.removeSuffix("Plugin")
        val indexName = "${base}PluginIndex"

        val containingFile = clazz.containingFile
        val dependencies = if (containingFile != null) Dependencies(false, containingFile) else Dependencies(false)
        val file = codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = GENERATED_PACKAGE,
            fileName = indexName,
        )
        file.writer(Charsets.UTF_8).use { w ->
            w.write(
                """
                |package $GENERATED_PACKAGE
                |
                |import $PLUGIN_INDEX_FQN
                |import $PLUGIN_FQN
                |
                |/** Generated by kernel-ksp for @$ANNOTATION_FQN class `$pluginFqn`. Do not edit. */
                |public class $indexName : PluginIndex {
                |    public override fun plugins(): List<Plugin> = listOf($pluginFqn())
                |}
                """.trimMargin()
            )
            w.write("\n")
        }
        logger.info("kernel-ksp: generated $GENERATED_PACKAGE.$indexName for $pluginFqn")
    }
}

class PluginMarkerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        PluginMarkerProcessor(environment.codeGenerator, environment.logger)
}
