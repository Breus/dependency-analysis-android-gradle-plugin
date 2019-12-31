@file:Suppress("UnstableApiUsage")

package com.autonomousapps

import com.autonomousapps.internal.*
import com.autonomousapps.internal.asm.ClassReader
import kotlinx.metadata.*
import kotlinx.metadata.jvm.KotlinClassMetadata
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.util.*
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Algorithm:
 * 1. Parses the bytecode of all dependencies looking for inline members (functions or properties), and producing a
 *    report that associates these dependencies (see [Dependency]) with a set of imports
 *    ([ComponentWithInlineMembers.imports]) that would indicate use of an inline member. It is a best-guess heuristic.
 *    (So, `inline fun SpannableStringBuilder.bold()` gets associated with `androidx.core.text.bold` in the core-ktx
 *    module.)
 * 2. Parse all Kotlin source looking for imports that might be associated with an inline function
 * 3. Connect 1 and 2.
 */
@CacheableTask
open class InlineMemberExtractionTask @Inject constructor(
    objects: ObjectFactory,
    private val workerExecutor: WorkerExecutor
) : DefaultTask() {

    init {
        group = "verification"
        description = "Produces a report of dependencies that contribute used inline members"
    }

    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    val artifacts: RegularFileProperty = objects.fileProperty()

    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    val kotlinSourceFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:OutputFile
    val inlineMembersReport: RegularFileProperty = objects.fileProperty()

    @get:OutputFile
    val inlineUsageReport: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun action() {
        workerExecutor.noIsolation().submit(InlineMemberExtractionWorkAction::class.java) {
            artifacts.set(this@InlineMemberExtractionTask.artifacts)
            kotlinSourceFiles.setFrom(this@InlineMemberExtractionTask.kotlinSourceFiles)
            inlineMembersReport.set(this@InlineMemberExtractionTask.inlineMembersReport)
            inlineUsageReport.set(this@InlineMemberExtractionTask.inlineUsageReport)
        }
    }
}

interface InlineMemberExtractionParameters : WorkParameters {
    val artifacts: RegularFileProperty
    val kotlinSourceFiles: ConfigurableFileCollection
    val inlineMembersReport: RegularFileProperty
    val inlineUsageReport: RegularFileProperty
}

abstract class InlineMemberExtractionWorkAction : WorkAction<InlineMemberExtractionParameters> {

    private val logger = Logging.getLogger(InlineMemberExtractionTask::class.java)

    override fun execute() {
        // Inputs
        val artifacts: List<Artifact> = parameters.artifacts.get().asFile.readText().fromJsonList()

        // Outputs
        val inlineMembersReportFile = parameters.inlineMembersReport.get().asFile
        inlineMembersReportFile.delete()
        val inlineUsageReportFile = parameters.inlineUsageReport.get().asFile
        inlineUsageReportFile.delete()

        val inlineImports: Set<ComponentWithInlineMembers> = artifacts
            .map { artifact ->
                Objects.requireNonNull(artifact.file, "File must not be null")
                artifact to InlineMemberFinder(logger, ZipFile(artifact.file!!)).find().toSortedSet()
            }.filterNot { (_, imports) -> imports.isEmpty() }
            .map { (artifact, imports) -> ComponentWithInlineMembers(artifact.dependency, imports) }
            .toSortedSet()

        inlineMembersReportFile.writeText(inlineImports.toJson())

        val usedComponents = InlineUsageFinder(parameters.kotlinSourceFiles, inlineImports).find()
        logger.debug("Inline usage:\n${usedComponents.toPrettyString()}")
        inlineUsageReportFile.writeText(usedComponents.toJson())
    }
}

private class InlineMemberFinder(
    private val logger: Logger,
    private val zipFile: ZipFile
) {

    /**
     * Returns either an empty list, if there are no inline members, or a list of import candidates. E.g.:
     * ```
     * [
     *   "kotlin.jdk7.*",
     *   "kotlin.jdk7.use"
     * ]
     * ```
     * An import statement with either of those would import the `kotlin.jdk7.use()` inline function, contributed by the
     * "org.jetbrains.kotlin:kotlin-stdlib-jdk7" module.
     */
    fun find(): List<String> {
        val entries = zipFile.entries().toList()
        // Only look at jars that have actual Kotlin classes in them
        if (entries.find { it.name.endsWith(".kotlin_module") } == null) {
            return emptyList()
        }

        return entries
            .filter { it.name.endsWith(".class") }
            .flatMap { entry ->
                // TODO an entry with `META-INF/proguard/androidx-annotations.pro`
                val classReader = zipFile.getInputStream(entry).use { ClassReader(it.readBytes()) }
                val metadataVisitor = KotlinMetadataVisitor(logger)
                classReader.accept(metadataVisitor, 0)

                val inlineMembers = metadataVisitor.builder?.let { header ->
                    when (val metadata = KotlinClassMetadata.read(header.build())) {
                        is KotlinClassMetadata.Class -> inlineMembers(metadata.toKmClass())//isAnyMemberInline(metadata.toKmClass())
                        is KotlinClassMetadata.FileFacade -> inlineMembers(metadata.toKmPackage())//isAnyMemberInline(metadata.toKmPackage())
                        is KotlinClassMetadata.MultiFileClassPart -> inlineMembers(metadata.toKmPackage())//isAnyMemberInline(metadata.toKmPackage())
                        is KotlinClassMetadata.SyntheticClass -> {
                            logger.debug("Ignoring SyntheticClass $entry")
                            emptyList()
                        }
                        is KotlinClassMetadata.MultiFileClassFacade -> {
                            logger.debug("Ignoring MultiFileClassFacade $entry")
                            emptyList()
                        }
                        is KotlinClassMetadata.Unknown -> {
                            logger.debug("Ignoring Unknown $entry")
                            emptyList()
                        }
                        null -> {
                            logger.debug("Ignoring null $entry")
                            emptyList()
                        }
                    }
                } ?: emptyList()

                if (inlineMembers.isNotEmpty()) {
                    val pn = entry.name.substring(0, entry.name.lastIndexOf("/")).replace("/", ".")
                    listOf("$pn.*") + inlineMembers.map { name -> "$pn.$name" }
                } else {
                    emptyList()
                }
            }
    }

    private fun inlineMembers(kmClass: KmClass): List<String> {
        return inlineFunctions(kmClass.functions) + inlineProperties(kmClass.properties)
    }

    private fun inlineMembers(kmPackage: KmPackage): List<String> {
        return inlineFunctions(kmPackage.functions) + inlineProperties(kmPackage.properties)
    }

    private fun inlineFunctions(functions: List<KmFunction>): List<String> {
        return functions
            .filter { Flag.Function.IS_INLINE(it.flags) }
            .map { it.name }
    }

    private fun inlineProperties(properties: List<KmProperty>): List<String> {
        return properties
            .filter { Flag.PropertyAccessor.IS_INLINE(it.flags) }
            .map { it.name }
    }
}

private class InlineUsageFinder(
    private val kotlinSourceFiles: FileCollection,
    private val inlineImports: Set<ComponentWithInlineMembers>
) {

    /**
     * Looks at all the Kotlin source in the project and scans for any import that is for a known inline member.
     * Returns the set of [Dependency]s that contribute these used inline members.
     */
    fun find(): Set<Dependency> {
        // TODO this is extremely gross. Will refactor after I write some tests. An AST might be "nicer"
        val usedComponents = mutableSetOf<Dependency>()
        kotlinSourceFiles.map {
            it.readLines()
        }.forEach { lines ->
            inlineImports.forEach { comp ->
                comp.imports.forEach { import ->
                    lines.find { line -> line.startsWith("import $import") }?.let {
                        usedComponents.add(comp.dependency)
                    }
                }
            }
        }
        return usedComponents.toSortedSet()
    }
}