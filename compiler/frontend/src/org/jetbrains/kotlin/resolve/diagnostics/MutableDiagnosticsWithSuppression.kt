/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.resolve.diagnostics

import com.intellij.openapi.util.CompositeModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.util.CachedValueImpl
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.DiagnosticSink

class MutableDiagnosticsWithSuppression(
    private val suppressCache: KotlinSuppressCache,
    private val delegateDiagnostics: Diagnostics,
) : Diagnostics {
    private val diagnosticList = ArrayList<Diagnostic>()
    private var diagnosticsCallback: DiagnosticSink.DiagnosticsCallback? = null

    //NOTE: CachedValuesManager is not used because it requires Project passed to this object
    private val cache = CachedValueImpl {
        val allDiagnostics = delegateDiagnostics.noSuppression().all() + diagnosticList
        CachedValueProvider.Result(DiagnosticsWithSuppression(suppressCache, allDiagnostics), modificationTracker)
    }

    private fun readonlyView(): DiagnosticsWithSuppression = cache.value!!

    override val modificationTracker = CompositeModificationTracker(delegateDiagnostics.modificationTracker)

    override fun all(): Collection<Diagnostic> = readonlyView().all()
    override fun forElement(psiElement: PsiElement) = readonlyView().forElement(psiElement)
    override fun noSuppression() = readonlyView().noSuppression()

    override fun setCallback(callback: DiagnosticSink.DiagnosticsCallback) {
        diagnosticsCallback = callback
        delegateDiagnostics.setCallback(callback)
    }

    //essential that this list is readonly
    fun getOwnDiagnostics(): List<Diagnostic> {
        return diagnosticList
    }

    fun report(diagnostic: Diagnostic) {
        // TODO: [VD] diagnostic has to be reported via callback, but there are several of failures
        //  unable to render message for some diagnostics :
        //  e.g.: `Type parameter descriptor is not initialized: T declared in fooParam` (see [PsiCheckerTestGenerated$Checker.testRedeclaration])

//        if (this.suppressCache.filter.invoke(diagnostic)) {
//             diagnosticsCallback?.callback(diagnostic)
//        }

        diagnosticList.add(diagnostic)
        modificationTracker.incModificationCount()
    }

    fun clear() {
        diagnosticList.clear()
        modificationTracker.incModificationCount()
    }

    @TestOnly
    fun getReadonlyView(): DiagnosticsWithSuppression = readonlyView()
}
