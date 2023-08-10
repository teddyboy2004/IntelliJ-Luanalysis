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

package com.tang.intellij.lua.annotator

import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.codeInsight.intention.MakeParameterOptionalIntention
import com.tang.intellij.lua.comment.psi.*
import com.tang.intellij.lua.highlighting.LuaHighlightingData
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext

/**
 * LuaAnnotator
 * Created by TangZX on 2016/11/22.
 */
class LuaAnnotator : Annotator {
    private var myHolder: AnnotationHolder? = null
    private val luaVisitor = LuaElementVisitor()
    private val docVisitor = LuaDocElementVisitor()
    private var isModuleFile: Boolean = false

    companion object {
        private val STD_MARKER = Key.create<Boolean>("lua.std.marker")
    }

    override fun annotate(psiElement: PsiElement, annotationHolder: AnnotationHolder) {
        myHolder = annotationHolder
        if (psiElement is LuaDocPsiElement) {
            psiElement.accept(docVisitor)
        } else if (psiElement is LuaPsiElement) {
            val psiFile = psiElement.containingFile
            isModuleFile = (psiFile as? LuaPsiFile)?.let {
                psiFile.getModuleName(SearchContext.get(psiFile.project))
            } != null
            psiElement.accept(luaVisitor)
        }
        myHolder = null
    }

    private fun createInfoAnnotation(psi: PsiElement, msg: String? = null): AnnotationBuilder {
        val builder = if (msg != null) {
            myHolder!!.newAnnotation(HighlightSeverity.INFORMATION, msg)
        } else {
            myHolder!!.newSilentAnnotation(HighlightSeverity.INFORMATION)
        }

        return builder.range(psi)
    }

    internal inner class LuaElementVisitor : LuaVisitor() {

        override fun visitExprStat(o: LuaExprStat) {
            if (o.expression !is LuaCallExpr) {
                if (o.containingFile !is LuaExprCodeFragment) {
                    myHolder!!.newAnnotation(HighlightSeverity.ERROR, "syntax error").range(o).create()
                }
            } else {
                super.visitExprStat(o)
            }
        }

        override fun visitLocalFuncDefStat(o: LuaLocalFuncDefStat) {
            val name = o.nameIdentifier

            if (name != null) {
                createInfoAnnotation(name, "Local function \"${o.name}\"")
                        .textAttributes(LuaHighlightingData.LOCAL_VAR)
                        .create()
            }
        }

        override fun visitLocalDef(o: LuaLocalDef) {
            createInfoAnnotation(o, "Local variable \"${o.name}\"")
                    .textAttributes(LuaHighlightingData.LOCAL_VAR)
                    .create()

            arrayOf(o.close, o.const).forEach {
                if (it != null) {
                    myHolder!!.newSilentAnnotation(HighlightSeverity.INFORMATION)
                            .range(it)
                            .textAttributes(LuaHighlightingData.KEYWORD)
                            .create()
                }
            }
        }

        override fun visitTableField(o: LuaTableField) {
            super.visitTableField(o)

            val id = o.id
            if (id != null) {
                createInfoAnnotation(id)
                        .textAttributes(LuaHighlightingData.FIELD)
                        .create()
            }
        }

        override fun visitFuncDefStat(o: LuaFuncDefStat) {
            val name = o.nameIdentifier ?: return

            if (isModuleFile) {
                createInfoAnnotation(name, "Module function \"${o.name}\"")
                        .textAttributes(LuaHighlightingData.INSTANCE_METHOD)
                        .create()
            } else {
                createInfoAnnotation(name, "Global function \"${o.name}\"")
                        .textAttributes(LuaHighlightingData.GLOBAL_FUNCTION)
                        .create()
            }
        }

        override fun visitClassMethodName(o: LuaClassMethodName) {
            val id = o.id ?: return
            val textAttributes = if (o.dot != null) {
                LuaHighlightingData.STATIC_METHOD
            } else {
                LuaHighlightingData.INSTANCE_METHOD
            }
            createInfoAnnotation(id)
                    .textAttributes(textAttributes)
                    .create()
        }

        // 增加参数着色
        override fun visitParamDef(o: LuaParamDef) {
            super.visitParamDef(o)
            createInfoAnnotation(o.id, "Parameter : \"${o.name}\"")
                .textAttributes(LuaHighlightingData.PARAMETER)
                .create()
        }

        override fun visitNameExpr(o: LuaNameExpr) {
            val id = o.id

            val context = SearchContext.get(o.project)
            val res = resolve(context, o)

            if (res != null) { //std api highlighting
                val containingFile = res.containingFile
                if (LuaFileUtil.isStdLibFile(containingFile.virtualFile, o.project)) {
                    createInfoAnnotation(id, "Std apis")
                            .textAttributes(LuaHighlightingData.STD_API)
                            .create()
                    o.putUserData(STD_MARKER, true)
                    return
                }
            }

            if (res is LuaParamDef) {
                if (!checkUpValue(o)) {
                    createInfoAnnotation(id, "Parameter : \"${res.name}\"")
                            .textAttributes(LuaHighlightingData.PARAMETER)
                            .create()
                }
            } else if (res is LuaFuncDefStat) {
                val resolvedFile = res.containingFile
                if (resolvedFile !is LuaPsiFile || resolvedFile.getModuleName(context) == null) {
                    createInfoAnnotation(id, "Global function : \"${res.name}\"")
                            .textAttributes(LuaHighlightingData.GLOBAL_FUNCTION)
                            .create()
                } else {
                    createInfoAnnotation(id, "Module function : \"${res.name}\"")
                }
            } else {
                if (id.textMatches(Constants.WORD_SELF)) {
                    if (!checkUpValue(o)) {
                        createInfoAnnotation(id)
                                .textAttributes(LuaHighlightingData.SELF)
                                .create()
                    }
                } else if (res is LuaLocalDef) {
                    if (!checkUpValue(o)) {
                        createInfoAnnotation(id, "Local variable \"${o.name}\"")
                                .textAttributes(LuaHighlightingData.LOCAL_VAR)
                                .create()
                    }
                } else if (res is LuaLocalFuncDefStat) {
                    if (!checkUpValue(o)) {
                        createInfoAnnotation(id, "Local function \"${o.name}\"")
                                .textAttributes(LuaHighlightingData.LOCAL_VAR)
                                .create()
                    }
                } else {
                    if (isModuleFile) {
                        createInfoAnnotation(id, "Module field \"${o.name}\"")
                                .textAttributes(LuaHighlightingData.FIELD)
                                .create()
                    } else {
                        createInfoAnnotation(id, "Global variable \"${o.name}\"")
                                .textAttributes(LuaHighlightingData.GLOBAL_VAR)
                                .create()
                    }
                }
            }
        }

        private fun checkUpValue(o: LuaNameExpr): Boolean {
            val upValue = isUpValue(SearchContext.get(o.project), o)
            if (upValue) {
                myHolder?.newAnnotation(HighlightSeverity.INFORMATION, "Up-value \"${o.name}\"")
                        ?.range(o.id.textRange)
                        ?.textAttributes(LuaHighlightingData.UP_VALUE)
                        ?.create()
            }
            return upValue
        }

        override fun visitIndexExpr(o: LuaIndexExpr) {
            super.visitIndexExpr(o)
            val prefix = o.prefixExpression
            if (prefix is LuaNameExpr && prefix.getUserData(STD_MARKER) != null) {
                createInfoAnnotation(o, "Std apis")
                        .textAttributes(LuaHighlightingData.STD_API)
                        .create()
                o.putUserData(STD_MARKER, true)
            } else {
                val id = o.id
                if (id != null) {
                    val builder = createInfoAnnotation(id, null)
                    if (o.parent is LuaCallExpr) {
                        if (o.colon != null) {
                            builder.textAttributes(LuaHighlightingData.INSTANCE_METHOD)
                        } else {
                            builder.textAttributes(LuaHighlightingData.STATIC_METHOD)
                        }
                    } else {
                        if (o.colon != null) {
                            myHolder!!.newAnnotation(HighlightSeverity.ERROR, "Arguments expected")
                                    .range(o)
                                    .create()
                        } else {
                            builder.textAttributes(LuaHighlightingData.FIELD)
                        }
                    }
                    builder.create()
                }
            }
        }
    }

    internal inner class LuaDocElementVisitor : LuaDocVisitor() {
        override fun visitGenericTableTy(o: LuaDocGenericTableTy) {
            super.visitGenericTableTy(o)
            val psiTextOffset = o.textOffset
            myHolder!!.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(TextRange(psiTextOffset, psiTextOffset + 5)) // 5 == "table".length
                    .textAttributes(LuaHighlightingData.TYPE_REFERENCE)
                    .create()
        }

        override fun visitTagClass(o: LuaDocTagClass) {
            super.visitTagClass(o)
            createInfoAnnotation(o.id, null)
                    .textAttributes(LuaHighlightingData.CLASS_NAME)
                    .create()
        }

        override fun visitTagAlias(o: LuaDocTagAlias) {
            super.visitTagAlias(o)
            val id = o.id
            createInfoAnnotation(id, null)
                    .textAttributes(LuaHighlightingData.TYPE_ALIAS)
                    .create()
        }

        override fun visitTypeRef(o: LuaDocTypeRef) {
            createInfoAnnotation(o, null)
                    .textAttributes(LuaHighlightingData.TYPE_REFERENCE)
                    .create()
        }

        override fun visitTagField(o: LuaDocTagField) {
            super.visitTagField(o)
            val id = o.nameIdentifier
            if (id != null) {
                createInfoAnnotation(id, null)
                        .textAttributes(LuaHighlightingData.DOC_COMMENT_TAG_VALUE)
                        .create()
            }
        }

        override fun visitParamNameRef(o: LuaDocParamNameRef) {
            createInfoAnnotation(o, null)
                    .textAttributes(LuaHighlightingData.DOC_COMMENT_TAG_VALUE)
                    .create()
        }

        override fun visitPrimitiveTableTy(o: LuaDocPrimitiveTableTy) {
            createInfoAnnotation(o, null)
                    .textAttributes(LuaHighlightingData.TYPE_REFERENCE)
                    .create()
        }

        override fun visitFunctionParams(o: LuaDocFunctionParams) {
            var encounteredOptional = false

            o.functionParamList.forEach { param ->
                if (encounteredOptional) {
                    if (param.optional == null) {
                        myHolder!!
                            .newAnnotation(HighlightSeverity.ERROR, "Required parameters cannot follow optional parameters")
                            .range(param)
                            .withFix(MakeParameterOptionalIntention())
                            .create()
                    }
                } else if (param.optional != null) {
                    encounteredOptional = true
                }
            }
        }
    }
}
