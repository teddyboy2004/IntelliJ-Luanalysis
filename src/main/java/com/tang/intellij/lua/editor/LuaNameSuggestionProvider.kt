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

package com.tang.intellij.lua.editor

import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.codeStyle.SuggestedNameInfo
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.NameSuggestionProvider
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.refactoring.LuaRefactoringUtil
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.*

/**
 *
 * Created by TangZX on 2016/12/20.
 */
class LuaNameSuggestionProvider : NameSuggestionProvider {

    var keywords: HashSet<String>? = null

    companion object {
        fun fixName(oriName: String): String {
            return oriName.replace(".", "")
        }
    }


    private fun collectNames(context: SearchContext, type: ITy, collector: (name: String, suffix: String, preferLonger: Boolean) -> Unit) {
        when (type) {
            is ITyClass -> {
                if (!type.isAnonymous && type !is TyDocTable)
                    collector(fixName(type.className), "", false)
                Ty.processSuperClasses(context, type) { superType ->
                    val superClass = (if (superType is ITyGeneric) superType.base else superType) as? ITyClass
                    if (superClass != null && !superClass.isAnonymous) {
                        collector(fixName(superClass.className), "", false)
                    }
                    true
                }
            }
            is ITyArray -> collectNames(context, type.base) { name, _, _ ->
                collector(name, "List", false)
            }
            is ITyGeneric -> {
                val paramTy = type.getArgTy(1)
                collectNames(context, paramTy) { name, _, _ ->
                    collector(name, "Map", false)
                }
            }
        }
    }

    //逆向
    private fun getNames(ref: PsiReference, set: MutableSet<String>) {
        val ele = ref.element
        val p1 = ele.parent
        if (ele is LuaExpression<*>) {
            when (p1) {
                is LuaListArgs -> {
                    //call(var)
                    val paramIndex = p1.getIndexFor(ele)
                    val p2 = p1.parent as? LuaCallExpr
                    if (p2 != null) {
                        val context = SearchContext.get(ele.project)
                        val ty = p2.guessParentType(context)

                        if (ty == null) {
                            return
                        }

                        TyUnion.each(ty) { iTy ->
                            iTy.processSignatures(context) { sig ->
                                sig.params?.getOrNull(paramIndex)?.let { paramInfo ->
                                    set.add(paramInfo.name)
                                }
                                return@processSignatures false
                            }
                        }
                    }
                }
                is LuaExprList -> {
                    //xxx = var
                    val p2 = p1.parent as? LuaAssignStat
                    val valueList = p2?.valueExprList?.expressionList
                    if (valueList != null) {
                        val index = valueList.indexOf(ele)
                        val varExpr = p2.getExpressionAt(index)
                        if (varExpr is LuaIndexExpr) varExpr.name?.let { set.add(it) }
                    }
                }
            }
        }
    }

    override fun getSuggestedNames(psi: PsiElement, nameSuggestionContext: PsiElement?, set: MutableSet<String>): SuggestedNameInfo? {
        val search = ReferencesSearch.search(psi, psi.useScope)
        search.forEach { getNames(it, set) }

        if (psi is LuaPsiTypeGuessable) {
            if (keywords == null) {
                keywords  = initKeywords()
            }
            val context = SearchContext.get(psi.getProject())
            val type = psi.guessType(context)
            if (type != null) {
                val names = HashSet<String>()
                 TyUnion.each(type) { ty ->
                    collectNames(context, ty) { name, suffix, preferLonger ->
                        // 避免出现错误提示
                        if (name.contains(":")) {
                            return@collectNames
                        }
                        if (names.add(name)) {
                            val strings = NameUtil.getSuggestionsByName(name, "", suffix, false, preferLonger, false)
                            set.addAll(strings)
                        }
                    }
                }
                // 优化变量名提示
                if (set.isEmpty()) {
                    if (type is TyLazySubstitutedTable) {
                        val defStat = PsiTreeUtil.getParentOfType(type.psi, LuaLocalDefStat::class.java)
                        val name = defStat?.localDefList?.get(0)?.name
                        if (name != null) {
                            set.add(name)
                        }

                    }
                    if (psi is LuaLocalDef) {
                        val parent = PsiTreeUtil.getParentOfType(psi, LuaLocalDefStat::class.java)
                        val last = parent?.exprList?.lastChild
                        if (last is LuaIndexExpr) {
                            set.add(last.name.toString());
                        } else if (last is LuaNameExpr) {
                            set.add(last.text)
                        }
                    }
                    val wordsScanner = LanguageFindUsages.INSTANCE.forLanguage(LuaLanguage.INSTANCE).wordsScanner
                    wordsScanner?.processWords(psi.containingFile.text) {
                        val word = it.baseText.subSequence(it.start, it.end).toString()
                        if (word.length > 2 && LuaRefactoringUtil.isLuaIdentifier(word) && !keywords!!.contains(word)) {
                            set.add(word)
                        }
                        true
                    }
                }
            }
        }
        return null
    }

    private fun initKeywords(): HashSet<String> {
        val set = HashSet<String>()
        LuaTypes::class.java.fields.forEach {
            field ->
            val data = field.get(null)
            if (data is LuaTokenType && LuaRefactoringUtil.isLuaIdentifier(data.debugName))
            {
                set.add(data.debugName)
            }
        }
        return set
    }
}
