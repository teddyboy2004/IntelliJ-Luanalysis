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
import com.intellij.codeInsight.template.impl.VariableNode
import com.intellij.psi.PsiFile
import com.tang.intellij.lua.codeInsight.template.context.LuaFunContextType
import com.tang.intellij.lua.psi.*

class LuaCurrentFunctionNameMacro : Macro() {
    override fun getPresentableName() = "LuaCurrentFunctionName(short)"

    override fun getName() = "LuaCurrentFunctionName"

    fun removeClassName(classMethodName: String): String
    {
        return classMethodName.replace(Regex(".*[.:]"), "")
    }

    override fun calculateResult(expressions: Array<out Expression>, context: ExpressionContext?): Result? {
        var param:String? = null
        if (expressions.isNotEmpty() && expressions.first() is VariableNode) {
            val expression = expressions.first() as VariableNode
            param = expression.name
        }
        var e = context?.psiElementAtStartOffset
        while (e != null && e !is PsiFile) {
            e = e.parent
            when (e) {
                is LuaClassMethodDefStat -> {
                    val classMethodName = e.classMethodName.text
                    return if (param == "true") {
                        TextResult(removeClassName(classMethodName))
                    } else {
                        TextResult(classMethodName)
                    }
                }
                is LuaFuncDefStat -> return TextResult(e.name ?: "")
                is LuaLocalFuncDefStat -> return TextResult(e.name ?: "")
            }
        }
        return null
    }

    override fun calculateLookupItems(params: Array<out Expression>, context: ExpressionContext?): Array<LookupElement>? {
        var param:String? = null
        if (params.isNotEmpty() && params.first() is VariableNode) {
            val expression = params.first() as VariableNode
            param = expression.name
        }
        var e = context?.psiElementAtStartOffset
        val list = mutableListOf<LookupElement>()
        while (e != null && e !is PsiFile) {
            e = e.parent
            when (e) {
                is LuaClassMethodDefStat -> {
                    val classMethodName = e.classMethodName.text
                    if (param == "true")
                    {
                        return arrayOf(LookupElementBuilder.create(removeClassName(classMethodName)))
                    }
                    else if (param == "false")
                    {
                        return arrayOf(LookupElementBuilder.create(classMethodName))
                    }
                    list.add(LookupElementBuilder.create(removeClassName(classMethodName)))
                    list.add(LookupElementBuilder.create(classMethodName))
                }

                is LuaFuncDefStat -> e.name?.let { list.add(LookupElementBuilder.create(it)) }
                is LuaLocalFuncDefStat -> e.name?.let { list.add(LookupElementBuilder.create(it)) }
            }
        }
        return list.toTypedArray()
    }

    override fun isAcceptableInContext(context: TemplateContextType): Boolean {
        return context is LuaFunContextType
    }
}
