package org.bazelkls

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile

class JvmNameExtractor {
    data class SourceFileJvmMapping(
        val sourceFile: String,
        val jvmClassNames: Set<String>,
        val packageName: String
    )

    companion object {
        fun extractMappings(classJars: List<String>): List<SourceFileJvmMapping> {
            val sourceToJvmMap = mutableMapOf<String, MutableSet<String>>()
            val sourceToPackageMap = mutableMapOf<String, String>()

            classJars.filter { it.isNotBlank() }.forEach { jarPath ->
                val jarFile = File(jarPath)
                if (jarFile.exists()) {
                    try {
                        scanJar(jarFile, sourceToJvmMap, sourceToPackageMap)
                    } catch (e: Exception) {
                        println("Error scanning jar $jarPath: ${e.message}")
                    }
                }
            }

            return sourceToJvmMap.map { (sourceFile, jvmNames) ->
                // If we have a package name for this source, use it, otherwise derive from first class
                val packageName = sourceToPackageMap[sourceFile] ?: derivePackageFromClassNames(jvmNames)
                SourceFileJvmMapping(sourceFile, jvmNames, packageName)
            }
        }

        private fun derivePackageFromClassNames(classNames: Set<String>): String {
            // Use the first class name to determine the package
            return classNames.firstOrNull()?.let { className ->
                val lastDotIndex = className.lastIndexOf('.')
                if (lastDotIndex > 0) className.substring(0, lastDotIndex) else ""
            } ?: ""
        }

        private fun scanJar(
            jarFile: File,
            sourceToJvmMap: MutableMap<String, MutableSet<String>>,
            sourceToPackageMap: MutableMap<String, String>
        ) {
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { entry ->
                        try {
                            jar.getInputStream(entry).use { input ->
                                val classBytes = input.readBytes()
                                processClass(classBytes, sourceToJvmMap, sourceToPackageMap)
                            }
                        } catch (e: Exception) {
                            println("Error processing class ${entry.name}: ${e.message}")
                        }
                    }
            }
        }

        private fun processClass(
            classBytes: ByteArray,
            sourceToJvmMap: MutableMap<String, MutableSet<String>>,
            sourceToPackageMap: MutableMap<String, String>
        ) {
            val classReader = ClassReader(classBytes)
            val jvmClassName = classReader.className.replace('/', '.')

            // Extract package name from the class name
            val packageName = jvmClassName.substringBeforeLast('.', "")

            classReader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitSource(source: String?, debug: String?) {
                    if (source != null) {
                        // Add this class to the source file mapping
                        sourceToJvmMap.computeIfAbsent(source) { mutableSetOf() }.add(jvmClassName)

                        // Store the package name for this source file
                        // Note: If multiple classes in different packages are in the same source file,
                        // the last one processed will win. This is rare in Kotlin but can happen.
                        sourceToPackageMap[source] = packageName
                    }
                    super.visitSource(source, debug)
                }
            }, ClassReader.SKIP_FRAMES)
        }
    }
}