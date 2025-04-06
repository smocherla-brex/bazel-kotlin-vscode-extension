package org.bazelkls

import org.bazelkls.proto.KotlinLsp
import org.bazelkls.proto.KotlinLsp.KotlinLspBazelTargetInfo
import org.bazelkls.proto.KotlinLsp.SourceFile
import org.bazelkls.proto.KotlinLsp.ClassPathEntry as ClassPathEntryProto
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.google.protobuf.util.JsonFormat
import java.io.File


/**
 * ExtractLspInfo extracts information required for the language server for a single bazel
 * target and its dependencies
 */

class ExtractLspInfo : CliktCommand() {
    private val gson = GsonBuilder().create()

    // Define the type for JSON parsing
    private val classPathEntryType = object : TypeToken<List<org.bazelkls.ClassPathEntry>>() {}.type

    val bazelTarget by option( "--target", help="The bazel target")

    val bazelTargetKind by option("--kind", help = "The bazel target kind").required()
    val sourceFiles by option( "--source-files", help="The direct source files for this target")
        .split(",")

    private val classPathEntries by option("--classpath", help = "JSON array of classpath entries for this target")
        .convert { jsonString ->
            gson.fromJson(jsonString, classPathEntryType) as List<org.bazelkls.ClassPathEntry>
        }

    private val bzlModEnabled by option("--bzlmod-enabled", help = "If bzlmod is enabled or not.").boolean().default(false)

    private val outputFile by option("--target-info", help = "The output file containing the target info in proto format")

    private val classJars by option("--class-jars", help = "Comma-separated list of class jars to analyze")
        .split(",")
        .default(emptyList())

    override fun run() {

        val classPathEntriesProtos = classPathEntries?.map {
            ClassPathEntryProto.newBuilder()
                .setCompileJar(it.compile_jar ?: "")
                .setSourceJar(it.source_jar ?: "")
                .build()
        }

        val jvmNameMappings = JvmNameExtractor.extractMappings(classJars)
        val filenameToJvmNames = jvmNameMappings.associate {
            it.sourceFile to it.jvmClassNames
        }

        val filenameToPackage = jvmNameMappings.associate {
            it.sourceFile to it.packageName
        }

        // When creating source file protos, include the JVM class names
        // and the package
        val sourceFilesProtos = sourceFiles?.map { fullSourcePath ->
            // Extract just the filename
            val filename = fullSourcePath.substringAfterLast('/')

            // Look up JVM names for this source file
            val jvmNames = filenameToJvmNames[filename] ?: emptySet()

            // Look up the package for the source file
            val packageName = filenameToPackage[filename] ?: ""

            SourceFile.newBuilder()
                .setPath(fullSourcePath)
                .addAllJvmClassNames(jvmNames)
                .setPackage(packageName)
                .build()
        }

        val packageMappings = generatePackageMappings()

        val packageMappingProtos = packageMappings.map { mapping ->
            KotlinLsp.PackageSourceMapping.newBuilder()
                .setPackageName(mapping.packageName)
                .setSourceJarPath(mapping.sourceJarPath)
                .build()
        }


        val targetInfo = KotlinLspBazelTargetInfo.newBuilder()
            .setBazelTarget(bazelTarget)
            .setBzlmodEnabled(bzlModEnabled)
            .addAllClasspath(classPathEntriesProtos)
            .addAllSourceFiles(sourceFilesProtos)
            .addAllPackageSourceMappings(packageMappingProtos)
            .build()


        outputFile?.let {
            File(it).writer().use { writer ->
                writer.write(JsonFormat.printer().print(targetInfo))
            }
        }

    }


    /**
     * Generate package to source jar mappings
     */
    private fun generatePackageMappings(): List<PackageInfoExtractor.PackageSourceMapping> {
        val packageMappings = mutableListOf<PackageInfoExtractor.PackageSourceMapping>()

        val jarToSourceMap = classPathEntries?.associate {
            (it.compile_jar ?: "") to (it.source_jar ?: "")
        } ?: mapOf()

        classJars.filter { it.isNotBlank() }.forEach { jar ->
            val sourceJarPath = jarToSourceMap[jar]
            val jarFile = File(jar)
            if (jarFile.exists() && !sourceJarPath.isNullOrBlank()) {
                val mappings = PackageInfoExtractor.extractPackageMappings(jarFile, sourceJarPath)
                packageMappings.addAll(mappings)
            }
        }

        if(!bazelTargetKind.contains("proto_") && !bazelTargetKind.contains("grpc_") ) {
            return packageMappings
        }

        // for protos, it's not trivial to get the source/compile jar mapping
        // so we go through all possible compile jars and generate this

        // Collect all unmapped source jars
        val unmappedSourceJars = classPathEntries
            ?.filter { it.compile_jar.isNullOrBlank() && !it.source_jar.isNullOrEmpty() }
            ?.mapNotNull { it.source_jar }
            ?.filter { it.isNotEmpty()}
            ?: emptyList()

        // Get all available compile jars
        val availableCompileJars = classPathEntries
            ?.mapNotNull { it.compile_jar }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // Create a mapping from normalized names to compile jars
        val normalizedCompileJarMap = mutableMapOf<String, String>()
        availableCompileJars.forEach { compileJarPath ->
            val normalizedName = normalizeProtoPath(compileJarPath)
            normalizedCompileJarMap[normalizedName] = compileJarPath
        }


        // Match unmapped source jars to compile jars using normalized names
        unmappedSourceJars.forEach { sourceJarPath ->
            val normalizedSourceName = normalizeProtoPath(sourceJarPath)
            val matchingCompileJar = normalizedCompileJarMap[normalizedSourceName]

            if (matchingCompileJar != null) {
                val compileJarFile = File(matchingCompileJar)
                if (compileJarFile.exists()) {
                    val mappings = PackageInfoExtractor.extractPackageMappings(compileJarFile, sourceJarPath)
                    packageMappings.addAll(mappings)
                }
            } else {
                print("Didnt find matching compile jar for ${sourceJarPath}")
            }
        }

        return packageMappings
    }

    /**
     * Normalize a proto jar path to a canonical form for matching
     */
    private fun normalizeProtoPath(path: String): String {
        // Extract the base path and filename
        val basePath = path.substringBeforeLast("/")
        val fileName = path.substringAfterLast("/")

        // Handle various patterns to extract the core proto name
        val protoName = when {
            // Compile jar patterns
            fileName.startsWith("lib") && fileName.contains("-speed-hjar") ->
                fileName.removePrefix("lib").substringBefore("-speed-hjar")
            fileName.startsWith("lib") && fileName.contains("-hjar") ->
                fileName.removePrefix("lib").substringBefore("-hjar")
            fileName.startsWith("lib") && fileName.contains("-speed") ->
                fileName.removePrefix("lib").substringBefore("-speed")

            // Source jar patterns
            fileName.contains("-speed-src") ->
                fileName.substringBefore("-speed-src")
            fileName.contains("-src") ->
                fileName.substringBefore("-src")

            // Default case
            else -> fileName.substringBefore(".jar")
        }

        // Return normalized path
        return "$basePath/$protoName"
    }
}

class LspInfoExtractor {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) = ExtractLspInfo().main(args)
    }
}
