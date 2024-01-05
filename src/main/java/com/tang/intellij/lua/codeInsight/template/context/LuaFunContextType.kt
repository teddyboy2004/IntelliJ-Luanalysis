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

package com.tang.intellij.lua.codeInsight.template.context

import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.util.prevLeaf
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.psi.LuaFuncBody

class LuaFunContextType : TemplateContextType("LUA_FUNCTION", "function", LuaCodeContextType::class.java) {

    override fun isInContext(psiFile: PsiFile, i: Int): Boolean {
        if (PsiTreeUtil.findElementOfClassAtOffset(psiFile, i, PsiComment::class.java, false) != null) {
            return false

        }
        if (PsiUtilCore.getLanguageAtOffset(psiFile, i).isKindOf(LuaLanguage.INSTANCE)) {
            val element = psiFile.findElementAt(i)
            // 避免在.或:后提示
            val prevLeaf = element?.prevLeaf()
            if (prevLeaf != null && (prevLeaf.text == "." || prevLeaf.text == ":")) {
                return false
            }
        }
        return PsiTreeUtil.findElementOfClassAtOffset(psiFile, i, LuaFuncBody::class.java, false) != null
    }
}