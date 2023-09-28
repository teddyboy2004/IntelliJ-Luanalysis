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
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.impl.VariableNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.codeInsight.template.context.LuaFunContextType
import com.tang.intellij.lua.psi.*

class LuaFunctionParamSigMacro : Macro() {
    override fun getPresentableName() = "LuaFunctionParamSignature(pre, post)"

    override fun getName() = "LuaFunctionParamSignature"

    private fun checkLuaBlockIsDot(e:PsiElement?):Boolean {
        var isDot = false
        if (e?.parent is LuaBlock) {
            val block = e.parent
            val luaIndexExpr = PsiTreeUtil.findChildOfType(block, LuaIndexExpr::class.java)
            if (luaIndexExpr!=null)
            {
                isDot = luaIndexExpr.dot != null
            }
        }
        return isDot
    }

    private fun getParam(e: LuaFuncBodyOwner<*>, isDot: Boolean, preStr: String, postStr: String):String
    {
        val str = e.paramSignature
        val methodName = PsiTreeUtil.getChildOfType(e, LuaClassMethodName::class.java)
        var p  = str.substring(1, str.length-1)
        if (p.isNotBlank() && (preStr.isNotBlank() || postStr.isNotBlank()))
        {
            val strings = p.split(",")
            val sb = StringBuilder()
            strings.forEach {
                if (sb.isNotEmpty())
                {
                    sb.append(", ")
                }
                if (preStr.isNotBlank()) {
                    sb.append(preStr)
                }
                sb.append(it.trim())
                if (postStr.isNotBlank())
                {
                    sb.append(postStr)
                }

            }
            p = sb.toString()
        }
        if (methodName != null && methodName.colon != null && isDot)
        {
            var sep = ""
            if(p.isNotEmpty())
            {
                sep = " ,"
            }
            p = "self$sep$p"
        }
        return p
    }

    override fun calculateResult(expressions: Array<out Expression>, context: ExpressionContext?): Result? {
        var (preStr, postStr) = getExpressionStringPair(expressions)
        var e = context?.psiElementAtStartOffset
        val isDot = checkLuaBlockIsDot(e)
        while (e != null && e !is PsiFile) {
            e = e.parent
            if (e is LuaFuncBodyOwner<*>) {
                return TextResult(getParam(e, isDot, preStr, postStr))
            }
        }
        return null
    }

    private fun getExpressionStringPair(expressions: Array<out Expression>): Pair<String, String> {
        var preStr = ""
        var postStr = ""
        if (expressions.isNotEmpty()) {
            preStr = expressions.first().calculateResult(null).toString()
            if (expressions.size > 1) {
                postStr = expressions[1].calculateResult(null).toString()
            }
        }
        return Pair(preStr, postStr)
    }

    override fun calculateLookupItems(params: Array<out Expression>, context: ExpressionContext?): Array<LookupElement>? {
        val (preStr, postStr) = getExpressionStringPair(params)
        var e = context?.psiElementAtStartOffset
        val isDot = checkLuaBlockIsDot(e)
        val list = mutableListOf<LookupElement>()
        while (e != null && e !is PsiFile) {
            e = e.parent
            if (e is LuaFuncBodyOwner<*>) {
                val param = getParam(e, isDot, preStr, postStr)
                list.add(LookupElementBuilder.create(param))
            }
        }
        return list.toTypedArray()
    }

    override fun isAcceptableInContext(context: TemplateContextType): Boolean {
        return context is LuaFunContextType
    }
}
