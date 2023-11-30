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

package com.tang.intellij.lua.editor.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.index.LuaUnknownClassMemberIndex
import com.tang.intellij.lua.ty.*

enum class MemberCompletionMode {
    Dot,    // self.xxx
    Colon,  // self:xxx()
    All     // self.xxx && self:xxx()
}

/**

 * Created by tangzx on 2016/12/25.
 */
open class ClassMemberCompletionProvider : LuaCompletionProvider() {
    protected abstract class HandlerProcessor {
        open fun processLookupString(lookupString: String, member: TypeMember, memberTy: ITy?): String = lookupString
        abstract fun process(element: LuaLookupElement, member: TypeMember, memberTy: ITy?): LookupElement
    }

    internal class OverrideInsertHandler() : InsertHandler<LookupElement> {
        override fun handleInsert(insertionContext: InsertionContext, lookupElement: LookupElement) {
            val startOffset = insertionContext.startOffset
            val element = insertionContext.file.findElementAt(startOffset)
            val editor = insertionContext.editor
            var needAppendPar = true
            //如果后面已经有()
            if (element != null) {
                val ex = editor as EditorEx
                val iterator = ex.highlighter.createIterator(startOffset)
                var prevIteratorEnd = iterator.end
                iterator.advance()
                if (!iterator.atEnd()) {
                    var tokenType = iterator.tokenType
                    while (tokenType === TokenType.WHITE_SPACE) {
                        iterator.advance()
                        if (iterator.atEnd())
                            break
                        prevIteratorEnd = iterator.end
                        tokenType = iterator.tokenType
                    }
                    //check : lookup-string<caret>expr()
                    if (tokenType === LuaTypes.LPAREN) {
                        needAppendPar = prevIteratorEnd != insertionContext.tailOffset
                    }
                }
            }

            if (needAppendPar) {
                // lookup-string<caret>expr() -> lookup-string(expr())
                val expr = findWarpExpr(insertionContext.file, startOffset)
                if (expr != null) {
                    val exprNode = expr.node
                    val endOffset = exprNode.startOffset + exprNode.textLength
                    if (endOffset > insertionContext.selectionEndOffset) {
                        editor.document.insertString(insertionContext.selectionEndOffset, "(")
                        editor.document.insertString(endOffset + 1, ")")
                        editor.caretModel.moveToOffset(endOffset + 2)
                        return
                    }
                } else {
                    val endOffset = startOffset + lookupElement.lookupString.length
                    editor.document.insertString(insertionContext.selectionEndOffset, "(")
                    editor.document.insertString(endOffset + 1, ")")
                    editor.caretModel.moveToOffset(endOffset + 1)
                    return
                }
            }
        }

        private fun findWarpExpr(file: PsiFile, offset: Int): LuaExpression<*>? {
            var expr = PsiTreeUtil.findElementOfClassAtOffset(file, offset, LuaExpression::class.java, true)
            while (expr != null) {
                val parent = expr.parent
                if (parent is LuaExpression<*> && parent.node.startOffset == offset) {
                    expr = parent
                } else break
            }
            return expr
        }

    }

    override fun addCompletions(session: CompletionSession) {
        val completionParameters = session.parameters
        val completionResultSet = session.resultSet

        val psi = completionParameters.position
        val indexExpr = psi.parent

        if (indexExpr is LuaIndexExpr) {
            val isColon = indexExpr.colon != null
            val project = indexExpr.project
            val context = SearchContext.get(project)
            val contextTy = LuaPsiTreeUtil.findContextClass(context, indexExpr)
            val prefixType = indexExpr.guessParentType(context)
            if (!Ty.isInvalid(prefixType)) {
                complete(context, isColon, contextTy, prefixType, completionResultSet, completionResultSet.prefixMatcher, null)
            }
            //smart
            val nameExpr = indexExpr.prefixExpression
            if (nameExpr is LuaNameExpr) {
                val colon = if (isColon) ":" else "."
                val prefixName = nameExpr.text
                val postfixName = indexExpr.name?.let { it.substring(0, it.indexOf(CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED)) }

                val matcher = completionResultSet.prefixMatcher.cloneWithPrefix(prefixName)
                LuaDeclarationTree.get(indexExpr.containingFile).walkUpLocal(indexExpr) { d ->
                    val it = d.firstDeclaration.psi
                    val txt = it.name
                    if (it is LuaPsiTypeGuessable && txt != null && prefixName != txt && matcher.prefixMatches(txt)) {
                        val type = it.guessType(context)
                        if (type != null) {
                            val prefixMatcher = completionResultSet.prefixMatcher
                            val resultSet = completionResultSet.withPrefixMatcher("$prefixName*$postfixName")
                            complete(context, isColon, contextTy, type, resultSet, prefixMatcher, object : HandlerProcessor() {
                                override fun process(element: LuaLookupElement, member: TypeMember, memberTy: ITy?): LookupElement {
                                    element.itemText = txt + colon + element.itemText
                                    element.lookupString = txt + colon + element.lookupString
                                    return PrioritizedLookupElement.withPriority(element, -2.0)
                                }
                            })
                        }
                    }
                    true
                }
            }
            // 显示未知调用
            if (LuaSettings.instance.isShowUnknownMethod) {
                var show: Boolean

                val last = indexExpr.expressionList.last()
                if (last is LuaNameExpr) {
                    show = last.isGlobal()
                }
                else {
                    val resolvedPrefixTy = Ty.resolve(context, prefixType)
                    show = resolvedPrefixTy is TyUnknown
                }
                if (show) {
                    var prefix = last.name ?: ""
                    if (prefix.isNotBlank()) {
                        prefix = prefix.replace(Regex("_.*"), "")
                        val allKeys = LuaUnknownClassMemberIndex.instance.getAllKeys(context.project)
                        val allKeySet = HashSet<String>()
                        var matchKeySet = HashSet<String>()
                        allKeys.forEach { name ->
                            if (name != null) {
                                var strings = name.split("*")
                                if (strings.size == 2) {
                                    if (strings[0] == prefix) {
                                        matchKeySet.add(strings[1])
                                    }
                                    allKeySet.add(strings[1])
                                }
                            }
                        }
                        matchKeySet.forEach() {
                            completionResultSet.addElement(
                                LookupElementBuilder.create(it)
                                    .withIcon(LuaIcons.CLASS_METHOD)
                                    .withInsertHandler(OverrideInsertHandler())
                                    .withTypeText("$prefix?", true)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun complete(
        context: SearchContext,
        isColon: Boolean,
        contextTy: ITy,
        prefixTy: ITy,
        completionResultSet: CompletionResultSet,
        prefixMatcher: PrefixMatcher,
        handlerProcessor: HandlerProcessor?
    ) {
        val mode = if (isColon) MemberCompletionMode.Colon else MemberCompletionMode.Dot
        val resolvedPrefixTy = Ty.resolve(context, prefixTy)

        if (resolvedPrefixTy is TyUnion) {
            addUnion(context, contextTy, resolvedPrefixTy, prefixTy, mode, completionResultSet, prefixMatcher, handlerProcessor)
        } else {
            addClass(context, contextTy, prefixTy, mode, completionResultSet, prefixMatcher, handlerProcessor)
        }
    }

    protected fun addUnion(
        context: SearchContext,
        contextTy: ITy,
        unionTy: TyUnion,
        prefixTy: ITy,
        completionMode: MemberCompletionMode,
        completionResultSet: CompletionResultSet,
        prefixMatcher: PrefixMatcher,
        handlerProcessor: HandlerProcessor?
    ) {
        var childTypes = unionTy.getChildTypes()
        var memberNameTypes = HashMap<String, ITy>()
        var typeMembers = HashMap<String, TypeMember>()
        childTypes.forEach { iTy ->
            if (iTy is TyClass) {
                iTy.processMembers(context) { curType, member ->
                    val curClass = (if (curType is ITyGeneric) curType.base else curType) as? ITyClass
                    if (curClass != null) {
                        member.name?.let { memberName ->
                            if (prefixMatcher.prefixMatches(memberName) && curClass.isVisibleInScope(context.project, contextTy, member.visibility)) {
                                var memberTy = member.guessType(context) ?: Primitives.UNKNOWN
                                var t = memberNameTypes[memberName]
                                if (t == null) {
                                    t = memberTy
                                    typeMembers[memberName] = member
                                } else {
                                    t = t.union(context, memberTy)
                                }
                                memberNameTypes[memberName] = t
                            }
                        }
                    }
                    true

                }
            }
        }

        memberNameTypes.forEach() { name, type ->
            addMember(
                context,
                completionResultSet,
                typeMembers[name]!!,
                type.getMemberSubstitutor(context),
                prefixTy,
                name,
                type,
                completionMode,
                handlerProcessor
            )
        }

//        val globalChildTys = unionTy.getChildTypes().filter { it.isGlobal }
//        val nonGlobalChildTys = unionTy.getChildTypes().filter { !it.isGlobal }
//
//        globalChildTys.forEach {
//            addClass(context, contextTy, it, completionMode, completionResultSet, prefixMatcher, handlerProcessor)
//        }
//
//        if (nonGlobalChildTys.isEmpty()) {
//            return
//        }
//
//        val firstChildTy = nonGlobalChildTys.first()
//        val subsequentChildTys = nonGlobalChildTys.drop(1)
//        val memberSubstitutor = firstChildTy.getMemberSubstitutor(context)
//
//        firstChildTy.processMembers(context) { curType, member ->
//            val curClass = (if (curType is ITyGeneric) curType.base else curType) as? ITyClass
//
//            if (curClass != null) {
//                member.name?.let { memberName ->
//                    if (prefixMatcher.prefixMatches(memberName) && curClass.isVisibleInScope(context.project, contextTy, member.visibility)) {
//                        var memberTy = member.guessType(context) ?: Primitives.UNKNOWN
//
//                        subsequentChildTys.forEach { childTy ->
//                            if (!childTy.isGlobal) {
//                                val ty = childTy.guessMemberType(context, memberName)
//
//                                if (ty == null) {
//                                    return@processMembers true
//                                }
//
//                                memberTy = memberTy.union(context, ty)
//                            }
//                        }
//
//                        addMember(
//                            context,
//                            completionResultSet,
//                            member,
//                            memberSubstitutor,
//                            prefixTy,
//                            memberName,
//                            memberTy,
//                            completionMode,
//                            handlerProcessor
//                        )
//                    }
//                }
//            }
//
//            true
//        }
    }

    protected fun addClass(
        context: SearchContext,
        contextTy: ITy,
        cls: ITy,
        completionMode: MemberCompletionMode,
        completionResultSet: CompletionResultSet,
        prefixMatcher: PrefixMatcher,
        handlerProcessor: HandlerProcessor?
    ) {
        cls.processMembers(context) { memberClass, member ->
            val curClass = (if (memberClass is ITyGeneric) memberClass.base else memberClass) as? ITyClass
            if (curClass != null) {
                val name = member.name ?: member.guessIndexType(context)?.let {
                    if (it is TyPrimitiveLiteral && it.primitiveKind == TyPrimitiveKind.String) {
                        it.value
                    } else {
                        null
                    }
                }

                name?.let { memberName ->
                    if (prefixMatcher.prefixMatches(memberName) && curClass.isVisibleInScope(context.project, contextTy, member.visibility)) {
                        addMember(
                            context,
                            completionResultSet,
                            member,
                            memberClass.getMemberSubstitutor(context),
                            memberClass,
                            memberName,
                            member.guessType(context) ?: Primitives.UNKNOWN,
                            completionMode,
                            handlerProcessor
                        )
                    }
                }
            }
            true
        }
    }

    protected fun addMember(
        context: SearchContext,
        completionResultSet: CompletionResultSet,
        member: TypeMember,
        memberSubstitutor: ITySubstitutor?,
        thisType: ITy,
        memberName: String,
        memberTy: ITy,
        completionMode: MemberCompletionMode,
        handlerProcessor: HandlerProcessor?
    ) {
        val bold = thisType == memberTy
        val className = thisType.displayName

        if (memberTy is ITyFunction) {
            val methodType = if (memberSubstitutor != null) {
                memberTy.substitute(context, memberSubstitutor)
            } else {
                memberTy
            }

            val fn = memberTy.substitute(context, TySelfSubstitutor(null, methodType))
            addFunction(completionResultSet, bold, completionMode != MemberCompletionMode.Dot, className, member, fn, thisType, thisType, handlerProcessor)
        } else if (member is LuaTypeField && completionMode != MemberCompletionMode.Colon) {
            val fieldType = if (memberSubstitutor != null) {
                memberTy.substitute(context, memberSubstitutor)
            } else {
                memberTy
            }

            addField(completionResultSet, bold, className, memberName, member, fieldType, handlerProcessor)
        }
    }

    protected fun addField(
        completionResultSet: CompletionResultSet,
        bold: Boolean,
        clazzName: String,
        fieldName: String,
        field: LuaTypeField,
        ty: ITy?,
        handlerProcessor: HandlerProcessor?
    ) {
        val element = LookupElementFactory.createFieldLookupElement(clazzName, fieldName, field, ty, bold)
        val ele = handlerProcessor?.process(element, field, null) ?: element
        completionResultSet.addElement(ele)
    }

    private fun addFunction(
        completionResultSet: CompletionResultSet,
        bold: Boolean,
        isColonStyle: Boolean,
        clazzName: String,
        classMember: TypeMember,
        ty: ITy,
        thisType: ITy,
        callType: ITy,
        handlerProcessor: HandlerProcessor?
    ) {
        val name = classMember.name
        if (name != null) {
            val context = SearchContext.get(classMember.psi.project)

            ty.processSignatures(context) {
                if (isColonStyle) {
                    val firstParamTy = it.getFirstParam(thisType, isColonStyle)?.ty
                    if (firstParamTy == null || !firstParamTy.contravariantOf(context, callType, TyVarianceFlags.STRICT_UNKNOWN)) {
                        return@processSignatures true
                    }
                }

                val lookupString = handlerProcessor?.processLookupString(name, classMember, ty) ?: name

                val element = LookupElementFactory.createMethodLookupElement(
                    clazzName,
                    lookupString,
                    classMember,
                    it,
                    bold,
                    isColonStyle,
                    ty,
                    LuaIcons.CLASS_METHOD
                )
                val ele = handlerProcessor?.process(element, classMember, ty) ?: element
                completionResultSet.addElement(ele)
                true
            }
        }
    }
}
