/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
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

package com.tang.intellij.lua.search

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectAndLibrariesScope
import com.tang.intellij.lua.ext.ILuaTypeInfer
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.LuaPsiTypeGuessable
import com.tang.intellij.lua.psi.ScopedTypeSubstitutor
import com.tang.intellij.lua.ty.ITy
import java.util.*

/**

 * Created by tangzx on 2017/1/14.
 */
abstract class SearchContext() {
    abstract val project: Project
    abstract val element: PsiElement?
    abstract val identifier: String

    open fun getProjectContext(): ProjectSearchContext {
        return ProjectSearchContext(this)
    }

    val index: Int get() = myIndex // Multiple results index
    val supportsMultipleResults: Boolean get() = myMultipleResults

    private var myDumb = contextStack.get().lastOrNull()?.isDumb ?: false
    private var myIndex = 0
    private var myMultipleResults = false
    private var myInStack = false
    private var myScope: GlobalSearchScope? = null
    private var myAbstractGenericScopeNames: Set<String>? = null

    protected constructor(sourceContext: SearchContext) : this() {
        myDumb = sourceContext.myDumb
        myIndex = sourceContext.myIndex
        myMultipleResults = sourceContext.myMultipleResults
        myInStack = sourceContext.myInStack
        myScope = sourceContext.myScope
    }

    fun <T> withIndex(index: Int, supportMultipleResults: Boolean = false, action: () -> T): T {
        val savedIndex = this.index
        val savedMultipleResults = this.supportsMultipleResults
        myIndex = index
        myMultipleResults = supportMultipleResults
        val ret = action()
        myIndex = savedIndex
        myMultipleResults = savedMultipleResults
        return ret
    }

    fun <T> withMultipleResults(action: () -> T): T {
        val savedIndex = this.index
        val savedMultipleResults = this.supportsMultipleResults
        myIndex = -1
        myMultipleResults = true
        val ret = action()
        myIndex = savedIndex
        myMultipleResults = savedMultipleResults
        return ret
    }

    fun <T> withListEntry(last: Boolean, action: () -> T): T {
        return if (last) {
            withMultipleResults(action)
        } else {
            withIndex(0, false, action)
        }
    }

    val scope get(): GlobalSearchScope {
        if (isDumb)
            return GlobalSearchScope.EMPTY_SCOPE
        if (myScope == null) {
            myScope = ProjectAndLibrariesScope(project)
        }
        return myScope!!
    }

    val abstractGenericScopeNames get(): Set<String>? {
        return myAbstractGenericScopeNames
    }

    val isDumb: Boolean
        get() = myDumb || DumbService.isDumb(project)

    fun <T> withScope(scope: GlobalSearchScope, action: () -> T): T {
        val oriScope = myScope
        myScope = scope
        val ret = action()
        myScope = oriScope
        return ret
    }

    fun <T> withAbstractGenericScopeNames(scopeNames: Iterable<String>, action: () -> T): T {
        val oldScopeNames = myAbstractGenericScopeNames
        myAbstractGenericScopeNames = scopeNames.toSet()
        val ret = action()
        myAbstractGenericScopeNames = oldScopeNames
        return ret
    }

    fun <T> withAbstractGenericScopeName(scopeName: String?, action: () -> T): T {
        val oldScopeNames = myAbstractGenericScopeNames
        myAbstractGenericScopeNames = scopeName?.let { setOf(scopeName) }
        val ret = action()
        myAbstractGenericScopeNames = oldScopeNames
        return ret
    }

    data class CacheStats(
        var hits: Int = 0,
        var missed: Int = 0,
        var skips: Int = 0
    ) {
        val resolutions = mapOf<String, Int>();
    }

    // Mapping type names to stats
    val cacheStats = mapOf<String, CacheStats>()

    private fun inferAndCache(psi: LuaPsiTypeGuessable): ITy? {
        return if (index == -1 || LuaSettings.instance.isUseGlobalCache) {
            val result = myInferCache.getOrDefault(psi, null) ?: ILuaTypeInfer.infer(this, psi)

            if (result != null) {
                myInferCache[psi] = result
            }

            result
        } else {
            ILuaTypeInfer.infer(this, psi)
        }
    }

    companion object {
        private val contextStack = ThreadLocal.withInitial { Stack<SearchContext>() }
        public val myInferCache = mutableMapOf<LuaPsiTypeGuessable, ITy>()
        fun get(project: Project): SearchContext {
            val stack = contextStack.get()

            return if (stack.isEmpty()) {
                ProjectSearchContext(project)
            } else {
                stack.peek()
            }
        }

        fun infer(psi: LuaPsiTypeGuessable): ITy? {
            return with(psi.project) { it.inferAndCache(psi) }
        }

        fun infer(context: SearchContext, psi: LuaPsiTypeGuessable): ITy? {
            return with(context, null) {
                it.inferAndCache(psi)?.let { ty ->
                    ScopedTypeSubstitutor.substitute(context, ty)
                }
            }
        }

        private fun <T> with(ctx: SearchContext, defaultValue: T, action: (ctx: SearchContext) -> T): T {
            return if (ctx.myInStack) {
                val result = action(ctx)
                result
            } else {
                val stack = contextStack.get()
                val size = stack.size
                stack.push(ctx)
                ctx.myInStack = true
                val result = try {
                    action(ctx)
                } catch (e: Exception) {
                    defaultValue
                }
                ctx.myInStack = false
                stack.pop()
                assert(size == stack.size)
                result
            }
        }

        private fun <T> with(project: Project, action: (ctx: SearchContext) -> T): T {
            val ctx = get(project)
            return with(ctx, action)
        }

        fun <T> withDumb(project: Project, defaultValue: T, action: (ctx: SearchContext) -> T): T {
            val context = ProjectSearchContext(project)
            return withDumb(context, defaultValue, action)
        }

        fun <T> withDumb(ctx: SearchContext, defaultValue: T, action: (ctx: SearchContext) -> T): T {
            return with(ctx, defaultValue) {
                val dumb = it.myDumb
                it.myDumb = true
                val ret = action(it)
                it.myDumb = dumb
                ret
            }
        }
    }

    class TypedHandlerHandler : TypedHandlerDelegate(){
        override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
            myInferCache.clear()
            return super.beforeCharTyped(c, project, editor, file, fileType)
        }
    }

    class BackspaceHandler : BackspaceHandlerDelegate() {
        override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
            myInferCache.clear()
        }
        override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
            return false
        }
    }
}
