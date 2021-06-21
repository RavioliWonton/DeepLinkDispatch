/*
 * Copyright (C) 2015 Airbnb, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.airbnb.deeplinkdispatch

import androidx.room.compiler.processing.XElement
import androidx.room.compiler.processing.XProcessingEnv
import androidx.room.compiler.processing.XRoundEnv
import androidx.room.compiler.processing.XTypeElement
import androidx.room.compiler.processing.isMethod
import com.airbnb.deeplinkdispatch.ProcessorUtils.decapitalize
import com.airbnb.deeplinkdispatch.ProcessorUtils.hasEmptyOrNullString
import com.airbnb.deeplinkdispatch.base.Utils
import com.airbnb.deeplinkdispatch.base.Utils.isConfigurablePathSegment
import com.google.auto.common.AnnotationMirrors
import com.google.auto.common.MoreElements
import com.google.auto.common.MoreTypes
import com.google.common.collect.FluentIterable
import com.google.common.collect.Sets
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.squareup.javapoet.*
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.MalformedURLException
import java.util.*
import java.util.stream.Collectors
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.*
import javax.tools.Diagnostic
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.reflect.KClass

class DeepLinkProcessor(symbolProcessorEnvironment: SymbolProcessorEnvironment? = null) : BaseProcessor(symbolProcessorEnvironment) {

    private val documentor by lazy {Documentor(environment)}

    private val incrementalMetadata: IncrementalMetadata? by lazy {
        if (environment.options[OPTION_INCREMENTAL].toBoolean()) {
            environment.options.get(OPTION_CUSTOM_ANNOTATIONS)?.let { customAnnotations ->
                IncrementalMetadata(
                    customAnnotations.split(",")
                        .mapNotNull { environment.findTypeElement(it) }.toSet()
                )
            } ?: IncrementalMetadata(emptySet())
        } else null
    }

    private val supportedBaseAnnotationClasses = setOf(
        DeepLink::class,
        DeepLinkHandler::class,
        DeepLinkModule::class,
    )

    private val supportedCustomAnnotationClasses by lazy { incrementalMetadata?.customAnnotations ?: emptySet()}

    private val supportedAnnotationClasses by lazy { supportedBaseAnnotationClasses + supportedCustomAnnotationClasses}

    override fun getSupportedAnnotationTypes(): Set<String> {
        return incrementalMetadata?.let {
            supportedAnnotationClasses.map { it::class.qualifiedName!! }.toSet()
        } ?: setOf("*")
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latestSupported()
    }

    override fun getSupportedOptions(): Set<String> {
        val supportedOptions = Sets.newHashSet(
            Documentor.DOC_OUTPUT_PROPERTY_NAME,
            OPTION_CUSTOM_ANNOTATIONS,
            OPTION_INCREMENTAL
        )
        if (incrementalMetadata != null) {
            supportedOptions.add("org.gradle.annotation.processing.aggregating")
        }
        return supportedOptions
    }

    override fun process(environment: XProcessingEnv, round: XRoundEnv) {
        try {
            val prefixes: Map<XTypeElement, Array<String>> =
                supportedCustomAnnotationClasses.map { customAnnotationTypeElement ->
                    if (customAnnotationTypeElement.isAnnotationClass()) {
                        error(
                            customAnnotationTypeElement,
                            "Only annotation types can be annotated with @${DEEP_LINK_SPEC_CLASS.simpleName}"
                        )
                    }
                    val prefix: Array<String> =
                        customAnnotationTypeElement.getAnnotation(DEEP_LINK_SPEC_CLASS)
                            ?.let { it.value.prefix } ?: emptyArray()
                    if (hasEmptyOrNullString(prefix)) {
                        error(
                            customAnnotationTypeElement,
                            "Prefix property cannot have null or empty strings"
                        )
                    }
                    if (prefix.isEmpty()) error(
                        customAnnotationTypeElement,
                        "Prefix property cannot be empty"
                    )
                    customAnnotationTypeElement to prefix
                }.toMap()
            val annotatedElements = (supportedCustomAnnotationClasses.map {
                round.getElementsAnnotatedWith(it.qualifiedName)
            }.flatten() + round.getElementsAnnotatedWith(DEEP_LINK_CLASS)).toSet()

            createDeeplinkDelegates(roundEnv = round)
//            createDeeplinkRegistries(
//                roundEnv = round,
//                annotatedElements = annotatedElements,
//                deepLinkElements = collectDeepLinkElements(annotatedElements, prefixes)
//            )
        } catch (e: DeepLinkProcessorException) {
            error(e.element, e.message ?: "")
        }
    }

//    private fun createDeeplinkRegistries(
//        roundEnv: XRoundEnv,
//        annotatedElements: Set<XElement>,
//        deepLinkElements: List<DeepLinkAnnotatedElement>
//    ) {
//        val deepLinkModuleElements = roundEnv.getElementsAnnotatedWith(DeepLinkModule::class.java)
//        deepLinkModuleElements.forEach{ deepLinkModuleElement ->
//            val packageName = environment.elementUtils.getPackageOf(deepLinkModuleElement).qualifiedName.toString()
//            try {
//                generateDeepLinkRegistry(
//                    packageName = packageName,
//                    className = deepLinkModuleElement.simpleName.toString(),
//                    deepLinkElements = deepLinkElements,
//                    originatingElements = annotatedElements + deepLinkModuleElement
//                )
//            } catch (e: IOException) {
//                environment.messager.printMessage(Diagnostic.Kind.ERROR, "Error creating file")
//            } catch (e: RuntimeException) {
//                val sw = StringWriter()
//                val pw = PrintWriter(sw)
//                e.printStackTrace(pw)
//                environment.messager.printMessage(
//                    Diagnostic.Kind.ERROR,
//                    "Internal error during annotation processing: $sw"
//                )
//            }
//        }
//    }

    private fun createDeeplinkDelegates(roundEnv: XRoundEnv) {
        val deepLinkHandlerElements = roundEnv.getElementsAnnotatedWith(DeepLinkHandler::class)
        deepLinkHandlerElements.forEach { deepLinkHandlerElement ->
            val annotationMirror =
                MoreElements.getAnnotationMirror(
                deepLinkHandlerElement,
                DeepLinkHandler::class.java
            )
            val annotationMirror2 = deepLinkHandlerElements.
            if (annotationMirror.isPresent) {
                val klasses = MoreAnnotationMirrors.getTypeValue(annotationMirror.get(), "value")
                val typeElements: List<TypeElement> =
                    FluentIterable.from(klasses).transform { klass ->
                        MoreTypes.asTypeElement(
                            klass
                        )
                    }.toList()
                val packageName = environment.elementUtils
                    .getPackageOf(deepLinkHandlerElement).qualifiedName.toString()
                try {
                    generateDeepLinkDelegate(packageName, typeElements, deepLinkHandlerElement)
                } catch (e: IOException) {
                    environment.messager.printMessage(Diagnostic.Kind.ERROR, "Error creating file")
                } catch (e: RuntimeException) {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    e.printStackTrace(pw)
                    environment.messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Internal error during annotation processing: $sw"
                    )
                }
            }
        }
    }

//    private fun collectDeepLinkElements(
//        elementsToProcess: Set<XElement>,
//        prefixes: Map<XTypeElement, Array<String>>
//    ): List<DeepLinkAnnotatedElement> {
//        return elementsToProcess.map { element ->
//            verifyElement(element)
//            if (element.isMethod()) {
//                verifyModifier(element)
//                verifyReturnType(element)
//            }
//            val deepLinkAnnotation = element.getAnnotation(DEEP_LINK_CLASS)
//            val deepLinks =
//                enumerateCustomDeepLinks(element, prefixes) + (deepLinkAnnotation?.value?.toList()
//                    ?: emptyList())
//            val type =
//                if (element.kind == ElementKind.CLASS) DeepLinkEntry.Type.CLASS else DeepLinkEntry.Type.METHOD
//            deepLinks.map {
//                try {
//                    DeepLinkAnnotatedElement(it, element, type)
//                } catch (e: MalformedURLException) {
//                    environment.messager.printMessage(
//                        Diagnostic.Kind.ERROR,
//                        "Malformed Deep Link URL $it"
//                    )
//                    null
//                }
//            }
//        }.flatten().filterNotNull()
//    }

//    private fun verifyElement(element: XElement) {
//        if (!element.isMethod() && element.kind != ElementKind.CLASS) {
//            error(
//                element,
//                "Only classes and methods can be annotated with @${DEEP_LINK_CLASS.simpleName}",
//            )
//        }
//    }

//    private fun verifyReturnType(element: XElement?) {
//        val returnType = MoreTypes.asTypeElement(element.MoreElements.asExecutable(element).returnType)
//        if (returnType.qualifiedName.toString() !in listOf(
//                "android.content.Intent",
//                "androidx.core.app.TaskStackBuilder",
//                "com.airbnb.deeplinkdispatch.DeepLinkMethodResult"
//            )
//        ) {
//            error(
//                element, ("Only `Intent`, `androidx.core.app.TaskStackBuilder` or "
//                        + "'com.airbnb.deeplinkdispatch.DeepLinkMethodResult' are supported. Please double "
//                        + "check your imports and try again.")
//            )
//        }
//    }

//    private fun verifyModifier(element: XElement) {
//        if (!element.modifiers.contains(Modifier.STATIC)) {
//            error(
//                element,
//                "Only static methods can be annotated with @${DEEP_LINK_CLASS.simpleName}",
//            )
//        }
//    }

    private fun error(e: XElement?, msg: String) {
        environment.messager.printMessage(Diagnostic.Kind.ERROR, msg, e)
    }

//    @Throws(IOException::class)
//    private fun generateDeepLinkRegistry(
//        packageName: String, className: String,
//        deepLinkElements: List<DeepLinkAnnotatedElement>,
//        originatingElements: Set<Element>
//    ) {
//        Collections.sort(deepLinkElements) { element1, element2 ->
//            deeplinkAnnotatedElementCompare(element1, element2)
//        }
//        documentor!!.write(deepLinkElements)
//        val urisTrie = Root()
//        val pathVariableKeys: MutableSet<String> = HashSet()
//        for (element: DeepLinkAnnotatedElement in deepLinkElements) {
//            val activity = ClassName.get(element.annotatedElement)
//            val uriTemplate = element.uri
//            try {
//                urisTrie.addToTrie(uriTemplate, activity.reflectionName(), element.method)
//            } catch (e: IllegalArgumentException) {
//                error(element.annotatedElement, e.message)
//            }
//            val deeplinkUri = DeepLinkUri.parseTemplate(uriTemplate)
//            //Keep track of pathVariables added in a module so that we can check at runtime to ensure
//            //that all pathVariables have a corresponding entry provided to BaseDeepLinkDelegate.
//            for (pathSegment: String in deeplinkUri.pathSegments()) {
//                if (isConfigurablePathSegment(pathSegment)) {
//                    pathVariableKeys.add(
//                        pathSegment.substring(
//                            configurablePathSegmentPrefix.length,
//                            pathSegment.length - configurablePathSegmentSuffix.length
//                        )
//                    )
//                }
//            }
//        }
//        val deepLinkRegistryBuilder = TypeSpec.classBuilder(
//            (className + REGISTRY_CLASS_SUFFIX)
//        ).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
//            .superclass(ClassName.get(BaseRegistry::class.java))
//        val stringMethodNames = getStringMethodNames(urisTrie, deepLinkRegistryBuilder)
//        val constructor = MethodSpec.constructorBuilder()
//            .addModifiers(Modifier.PUBLIC)
//            .addCode(
//                CodeBlock.builder()
//                    .add(
//                        "super(\$T.readMatchIndexFromStrings( new String[] {$stringMethodNames})",
//                        CLASS_UTILS
//                    ).build()
//            )
//            .addCode(generatePathVariableKeysBlock(pathVariableKeys))
//            .build()
//
//        // For debugging it is nice to have a file version of the index, just comment this in to get
//        // on in the classpath
////    FileObject indexResource = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
////    MatchIndex.getMatchIdxFileName(className));
////    urisTrie.writeToOutoutStream(indexResource.openOutputStream());
//        deepLinkRegistryBuilder.addMethod(constructor)
//        originatingElements.forEach{ originatingElement ->
//            deepLinkRegistryBuilder.addOriginatingElement(originatingElement)
//        }
//        JavaFile.builder(packageName, deepLinkRegistryBuilder.build()).build().writeTo(filer)
//    }

    private fun generatePathVariableKeysBlock(pathVariableKeys: Set<String>): CodeBlock {
        val pathVariableKeysBuilder = CodeBlock.builder()
        pathVariableKeysBuilder.add(
            ",\n" + "new \$T[]{",
            ClassName.get(String::class.java)
        )
        val pathVariableKeysArray = pathVariableKeys.toTypedArray()
        for (i in pathVariableKeysArray.indices) {
            pathVariableKeysBuilder.add("\$S", pathVariableKeysArray[i])
            if (i < pathVariableKeysArray.size - 1) {
                pathVariableKeysBuilder.add(", ")
            }
        }
        pathVariableKeysBuilder.add("});\n")
        return pathVariableKeysBuilder.build()
    }

    /**
     * Add methods containing the Strings to store the match index to the deepLinkRegistryBuilder and
     * return a string which contains the calls to those methods.
     *
     *
     * e.g. "method1(), method2()" etc.
     *
     * @param urisTrie                The [UrlTree] containing all Urls that can be matched.
     * @param deepLinkRegistryBuilder The builder used to add the methods
     * @return
     */
    private fun getStringMethodNames(
        urisTrie: Root,
        deepLinkRegistryBuilder: TypeSpec.Builder
    ): StringBuilder {
        var i = 0
        val stringMethodNames = StringBuilder()
        for (charSequence: CharSequence in urisTrie.getStrings()) {
            val methodName = "matchIndex$i"
            stringMethodNames.append(methodName).append("(), ")
            deepLinkRegistryBuilder.addMethod(
                MethodSpec.methodBuilder(methodName)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(String::class.java)
                    .addCode(CodeBlock.builder().add("return \$S;", charSequence).build()).build()
            )
            i++
        }
        return stringMethodNames
    }

//    @Throws(IOException::class)
//    private fun generateDeepLinkDelegate(
//        packageName: String,
//        registryClasses: List<TypeElement>,
//        originatingElement: Element
//    ) {
//        val moduleRegistriesArgument = CodeBlock.builder()
//        val totalElements = registryClasses.size
//        for (i in 0 until totalElements) {
//            moduleRegistriesArgument.add(
//                "\$L\$L",
//                decapitalize(moduleNameToRegistryName(registryClasses[i])),
//                if (i < totalElements - 1) ",\n" else ""
//            )
//        }
//        val registriesInitializerBuilder = CodeBlock.builder()
//            .add("super(\$T.asList(\n", ClassName.get(Arrays::class.java))
//            .indent()
//            .add(moduleRegistriesArgument.build())
//            .add("\n").unindent().add("));\n")
//            .build()
//        val registriesInitializerBuilderWithPathVariables = CodeBlock.builder()
//            .add("super(\$T.asList(\n", ClassName.get(Arrays::class.java))
//            .indent()
//            .add(moduleRegistriesArgument.build())
//            .add("),\nconfigurablePathSegmentReplacements").unindent().add("\n);\n")
//            .build()
//        val constructor = MethodSpec.constructorBuilder()
//            .addModifiers(Modifier.PUBLIC)
//            .addParameters(
//                FluentIterable.from(registryClasses).transform { typeElement ->
//                    ParameterSpec.builder(
//                        moduleElementToRegistryClassName(typeElement!!),
//                        decapitalize(moduleNameToRegistryName(typeElement))
//                    ).build()
//                }.toList()
//            )
//            .addCode(registriesInitializerBuilder)
//            .build()
//        val configurablePathSegmentReplacementsParam = ParameterSpec.builder(
//            ParameterizedTypeName.get(
//                MutableMap::class.java,
//                String::class.java,
//                String::class.java
//            ),
//            "configurablePathSegmentReplacements"
//        )
//            .build()
//        val constructorWithPathVariables = MethodSpec.constructorBuilder()
//            .addModifiers(Modifier.PUBLIC)
//            .addParameters(
//                registryClasses.stream().map { typeElement: TypeElement ->
//                    ParameterSpec.builder(
//                        moduleElementToRegistryClassName(typeElement),
//                        decapitalize(moduleNameToRegistryName(typeElement))
//                    )
//                        .build()
//                }
//                    .collect(Collectors.toList())
//            )
//            .addParameter(configurablePathSegmentReplacementsParam)
//            .addCode(registriesInitializerBuilderWithPathVariables)
//            .build()
//        val deepLinkDelegateBuilder = TypeSpec.classBuilder("DeepLinkDelegate")
//            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
//            .superclass(CLASS_BASE_DEEP_LINK_DELEGATE)
//            .addMethod(constructor)
//            .addMethod(constructorWithPathVariables)
//            .addOriginatingElement(originatingElement)
//        for (registryElement: TypeElement in registryClasses) {
//            deepLinkDelegateBuilder.addOriginatingElement(registryElement)
//        }
//        JavaFile.builder(packageName, deepLinkDelegateBuilder.build())
//            .build()
//            .writeTo(filer)
//    }

    private class IncrementalMetadata(val customAnnotations: Set<XTypeElement>)

    companion object {
        private const val PACKAGE_NAME = "com.airbnb.deeplinkdispatch"
        private const val OPTION_CUSTOM_ANNOTATIONS = "deepLink.customAnnotations"
        private const val OPTION_INCREMENTAL = "deepLink.incremental"
        private val CLASS_BASE_DEEP_LINK_DELEGATE =
            ClassName.get(PACKAGE_NAME, "BaseDeepLinkDelegate")
        private val CLASS_UTILS = ClassName.get(Utils::class.java)
        private val DEEP_LINK_CLASS = DeepLink::class
        private val DEEP_LINK_SPEC_CLASS = DeepLinkSpec::class
        const val REGISTRY_CLASS_SUFFIX = "Registry"

//        private fun enumerateCustomDeepLinks(
//            element: Element,
//            prefixesMap: Map<Element, Array<String>>
//        ): List<String> {
//            val annotationMirrors: Set<AnnotationMirror> =
//                AnnotationMirrors.getAnnotatedAnnotations(element, DEEP_LINK_SPEC_CLASS)
//            val deepLinks: MutableList<String> = ArrayList()
//            for (customAnnotation: AnnotationMirror in annotationMirrors) {
//                val suffixes: List<AnnotationValue> = MoreAnnotationMirrors.asAnnotationValues(
//                    AnnotationMirrors.getAnnotationValue(
//                        customAnnotation,
//                        "value"
//                    )
//                )
//                val customElement = customAnnotation.annotationType.asElement()
//                val prefixes = prefixesMap.get(customElement)
//                    ?: throw DeepLinkProcessorException(
//                        ("Unable to find annotation '"
//                                + customElement
//                                + "' you must update "
//                                + "'deepLink.customAnnotations' within the build.gradle"),
//                        customElement
//                    )
//                for (prefix: String? in prefixes) {
//                    for (suffix: AnnotationValue in suffixes) {
//                        deepLinks.add(prefix + suffix.value)
//                    }
//                }
//            }
//            return deepLinks
//        }

        private fun moduleNameToRegistryName(typeElement: TypeElement): String {
            return typeElement.simpleName.toString() + REGISTRY_CLASS_SUFFIX
        }

        private fun moduleElementToRegistryClassName(element: TypeElement): ClassName {
            return ClassName.get(
                getPackage(element).qualifiedName.toString(),
                element.simpleName.toString() + REGISTRY_CLASS_SUFFIX
            )
        }

        private fun getPackage(type: Element): PackageElement {
            return if (type.kind != ElementKind.PACKAGE) {
                getPackage(type.enclosingElement)
            } else {
                type as PackageElement
            }
        }

        private fun deeplinkAnnotatedElementCompare(
            element1: DeepLinkAnnotatedElement,
            element2: DeepLinkAnnotatedElement
        ): Int {
            val uri1 = DeepLinkUri.parseTemplate(element1.uri)
            val uri2 = DeepLinkUri.parseTemplate(element2.uri)
            var comparisonResult = uri2.pathSegments().size - uri1.pathSegments().size
            if (comparisonResult == 0) {
                comparisonResult = uri2.queryParameterNames().size - uri1.queryParameterNames().size
            }
            if (comparisonResult == 0) {
                comparisonResult = uri1.encodedPath().split("%7B".toRegex())
                    .toTypedArray().size - uri2.encodedPath().split("%7B".toRegex())
                    .toTypedArray().size
            }
            if (comparisonResult == 0) {
                val element1Representation =
                    element1.uri + element1.method + element1.annotationType
                val element2Representation =
                    element2.uri + element2.method + element2.annotationType
                comparisonResult = element1Representation.compareTo(element2Representation)
            }
            return comparisonResult
        }

    }
}