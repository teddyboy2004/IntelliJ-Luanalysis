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

package com.tang.intellij.lua.codeInsight.template.macro

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.*
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.childrenOfType
import com.tang.intellij.lua.codeInsight.template.context.LuaFunContextType
import com.tang.intellij.lua.psi.LuaAssignStat
import com.tang.intellij.lua.psi.LuaClassMethodDefStat
import com.tang.intellij.lua.psi.LuaLocalDefStat
import com.tang.intellij.lua.psi.guessType
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.TyClass
import com.tang.intellij.lua.ty.TyTable

class LuaCurrentClassNameMacro : Macro() {
    override fun getPresentableName() = "LuaCurrentClassName()"

    override fun getName() = "LuaCurrentClassName"

    fun removeFunction(classMethodName: String): String {
        return classMethodName.replace(Regex("[.:].*"), "")
    }

    override fun calculateResult(expressions: Array<out Expression>, context: ExpressionContext?): Result? {
        var e = context?.psiElementAtStartOffset
        while (e != null && e !is PsiFile) {
            e = e.parent
            when (e) {
                is LuaClassMethodDefStat -> {
                    var classMethodName = e.classMethodName.text
                    return TextResult(removeFunction(classMethodName))
                }
            }
        }
        return null
    }

    override fun calculateLookupItems(params: Array<out Expression>, context: ExpressionContext?): Array<LookupElement>? {
        var e = context?.psiElementAtStartOffset
        val srcElement = e
        val list = mutableListOf<LookupElement>()
        while (e != null && e !is PsiFile) {
            e = e.parent
            when (e) {
                is LuaClassMethodDefStat -> {
                    var classMethodName = e.classMethodName.text
                    list.add(LookupElementBuilder.create(removeFunction(classMethodName)))
                }
            }
        }
        // 补充在函数外的情况
        if (list.isEmpty() && srcElement != null) {
            // 前一级是文件节点，向上找函数定义
            if (srcElement.parent is PsiFile) {
                e = srcElement.prevSibling
                while (e != null) {
                    when (e) {
                        is LuaClassMethodDefStat -> {
                            val classMethodName = e.classMethodName.text
                            val lookupString = removeFunction(classMethodName)
                            if (lookupString.isNotBlank()) {
                                list.add(LookupElementBuilder.create(lookupString))
                            }
                            break
                        }
                    }
                    e = e.prevSibling
                }
            }
            // 还是找不到就找第一个 local xxx = {}
                val searchContext = SearchContext.get(srcElement.project)
            if (list.isEmpty()) {

                val declarations = srcElement.containingFile.childrenOfType<LuaLocalDefStat>().filter { stat->stat.exprList?.guessType(searchContext) is TyClass }
                declarations.forEach {
                    if (it.comment?.tagClass != null) {
                        list.add(LookupElementBuilder.create(it.localDefList.first().name))
                    }
                }
                if (list.isEmpty() && declarations.isNotEmpty())
                {
                    list.add(LookupElementBuilder.create(declarations.first().localDefList.first().name))
                }
            }
            // 还是没有就找第一个xxx = {}
            if (list.isEmpty()) {
                val stat = PsiTreeUtil.getChildrenOfType(srcElement.containingFile, LuaAssignStat::class.java)?.filter { stat-> stat.valueExprList?.guessType(searchContext) is TyClass }
                if (!stat.isNullOrEmpty()) {
                    list.add(LookupElementBuilder.create(stat.first().varExprList.firstChild.text))
                }
            }
        }
        return list.toTypedArray()
    }

    override fun isAcceptableInContext(context: TemplateContextType): Boolean {
        return context is LuaFunContextType
    }
}
