/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.jdk.junit5

import org.jetbrains.kotlin.codegen.jdk.SeparateJavaProcessHelper
import org.jetbrains.kotlin.codegen.jdk.performFilter
import org.jetbrains.kotlin.test.TestMetadata
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.notification.RunNotifier

class JUnitPlatformRunnerForJdk6(testClass: Class<*>) : JUnitPlatform(testClass) {
    init {
        performFilter(testClass, this) f@{ description ->
            @Suppress("NAME_SHADOWING")
            val testClass = description.testClass ?: return@f null
            val methodName = description.methodName ?: return@f null

            val testClassAnnotation = testClass.getAnnotation(TestMetadata::class.java) ?: return@f null
            val method = testClass.getMethod(methodName)
            val methodAnnotation = method.getAnnotation(TestMetadata::class.java) ?: return@f null
            "${testClassAnnotation.value}/${methodAnnotation.value}"
        }
    }

    override fun run(notifier: RunNotifier?) {
        SeparateJavaProcessHelper.setUp()
        try {
            super.run(notifier)
        } finally {
            SeparateJavaProcessHelper.tearDown()
        }
    }
}
