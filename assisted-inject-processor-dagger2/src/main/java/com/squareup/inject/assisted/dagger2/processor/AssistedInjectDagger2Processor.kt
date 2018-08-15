/*
 * Copyright (C) 2017 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.inject.assisted.dagger2.processor

import com.google.auto.service.AutoService
import com.squareup.inject.assisted.AssistedInject
import com.squareup.inject.assisted.dagger2.AssistedModule
import com.squareup.inject.assisted.processor.AssistedInjectRequest.Companion.SUFFIX
import com.squareup.inject.assisted.processor.internal.applyEach
import com.squareup.inject.assisted.processor.internal.cast
import com.squareup.inject.assisted.processor.internal.findElementsAnnotatedWith
import com.squareup.inject.assisted.processor.internal.hasAnnotation
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier.ABSTRACT
import javax.lang.model.element.Modifier.PRIVATE
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic.Kind.ERROR

@AutoService(Processor::class)
class AssistedInjectDagger2Processor : AbstractProcessor() {
  override fun getSupportedSourceVersion() = SourceVersion.latest()
  override fun getSupportedAnnotationTypes() = setOf(
      AssistedModule::class.java.canonicalName,
      AssistedInject.Factory::class.java.canonicalName)

  override fun init(env: ProcessingEnvironment) {
    super.init(env)
    this.messager = env.messager
    this.filer = env.filer
  }

  private lateinit var messager: Messager
  private lateinit var filer: Filer

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    val assistedModules = roundEnv.findElementsAnnotatedWith<AssistedModule>()
        .cast<TypeElement>()
    if (assistedModules.size > 1) {
      assistedModules.forEach {
        messager.printMessage(ERROR, "Multiple @AssistedModule-annotated modules found.", it)
      }
    }

    assistedModules.filterNot { it.hasAnnotation("dagger.Module") }
        .forEach {
          messager.printMessage(ERROR, "@AssistedModule must also be annotated with @Module.", it)
        }

    // TODO validate includes={} includes the generated type.

    assistedModules.firstOrNull()?.let {
      val moduleName = ClassName.get(it)
      val generatedName = moduleName.peerClass(PREFIX + moduleName.simpleName())

      val factoryNameMap = roundEnv.findElementsAnnotatedWith<AssistedInject.Factory>()
          .cast<TypeElement>()
          .associate { ClassName.get(it) to it.enclosingElement }
          .filterValues { it != null }
          .mapValues { (_, value) ->
            // TODO this is a bit gross. Create a data class holding type, factory, and generated.
            ClassName.get(value as TypeElement).peerClass(value.simpleName.toString() + SUFFIX)
          }

      val generatedSpec = TypeSpec.classBuilder(generatedName)
          .addAnnotation(MODULE)
          .addModifiers(ABSTRACT)
          .addMethod(MethodSpec.constructorBuilder()
              .addModifiers(PRIVATE)
              .build())
          .applyEach(factoryNameMap.entries) {
            addMethod(MethodSpec.methodBuilder(it.key.enclosingClassName().bindMethodName())
                .addAnnotation(BINDS)
                .addModifiers(ABSTRACT)
                .returns(it.key)
                .addParameter(it.value, "factory")
                .build())
          }
          .build()

      JavaFile.builder(generatedName.packageName(), generatedSpec)
          .addFileComment("Generated by @AssistedModule. Do not modify!")
          .build()
          .writeTo(filer)
    }

    return false
  }

  companion object {
    const val PREFIX = "AssistedInject_"
    private val BINDS = ClassName.get("dagger", "Binds")
    private val MODULE = ClassName.get("dagger", "Module")

    private fun ClassName.bindMethodName() = "bind_" + reflectionName().replace('.', '_')
  }
}
