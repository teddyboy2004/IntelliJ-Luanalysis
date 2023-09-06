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

package com.tang.intellij.lua.stubs.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.Processor
import com.intellij.util.containers.ContainerUtil
import com.tang.intellij.lua.psi.LuaIndexExpr
import com.tang.intellij.lua.psi.LuaPsiTypeMember
import com.tang.intellij.lua.psi.LuaTypeMethod
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.*

typealias ProcessLuaPsiClassMember = (ownerTy: ITyClass, member: LuaPsiTypeMember) -> Boolean

// TODO: Underlying processKey(...) logic is fragile/wrong. We should not be resolving/traversing classes looking for
//       keys in the index. A type we encounter may not store its members in the index (see TySubstitutedDocTable).
class LuaClassMemberIndex : StringStubIndexExtension<LuaPsiTypeMember>() {
    override fun getKey() = StubKeys.CLASS_MEMBER

    override fun get(s: String, project: Project, scope: GlobalSearchScope): Collection<LuaPsiTypeMember> =
            StubIndex.getElements(StubKeys.CLASS_MEMBER, s, project, scope, LuaPsiTypeMember::class.java)

    companion object {
        val instance = LuaClassMemberIndex()

        private fun processKey(context: SearchContext, type: ITyClass, key: String, process: ProcessLuaPsiClassMember): Boolean {
            if (!context.isDumb) {
                LuaClassMemberIndex.instance.get(key, context.project, context.scope).forEach {
                    ProgressManager.checkCanceled()

                    if (!process(type, it)) {
                        return false
                    }
                }
            }

            return true
        }

        private fun processClassKeys(
            context: SearchContext,
            owner: ITyClass,
            className: String,
            keys: Collection<String>,
            deep: Boolean,
            process: ProcessLuaPsiClassMember
        ): Boolean {
            keys.forEach { key ->
                val classKey = "$className$key"

                if (!processKey(context, owner, classKey, process)) {
                    return false
                }
            }

            owner.lazyInit(context)

            val notFound = owner.processAlias { aliasedName ->
                if (className == aliasedName) {
                    return@processAlias true
                }

                val aliasedTy = LuaClassIndex.find(context, aliasedName)?.type

                if (aliasedTy != null) {
                    LuaClassIndex.find(context, aliasedName)?.type?.let { aliasedClass ->
                        processClassKeys(context, aliasedClass, aliasedName, keys, deep, process)
                    } ?: true
                } else {
                    // Anonymous type not in the class index i.e. table expression
                    processClassKeys(context, owner, aliasedName, keys, false, process)
                }
            }

            if (!notFound) {
                return false
            }

            if (deep) {
                return Ty.processSuperClasses(context, owner) { superType ->
                    val superClass = (if (superType is ITyGeneric) superType.base else superType) as? ITyClass
                    if (superClass != null) {
                        processClassKeys(context, superClass, superClass.className, keys, true, process)
                    } else true
                }
            }

            return true
        }

        private fun processClassKeys(context: SearchContext, cls: ITyClass, keys: Collection<String>, deep: Boolean, process: ProcessLuaPsiClassMember): Boolean {
            return if (cls is TyGenericParameter) {
                (cls.superClass as? ITyClass)?.let { processClassKeys(context, it, it.className, keys, deep, process) } ?: true
            } else {
                processClassKeys(context, cls, cls.className, keys, deep, process)
            }
        }

        fun getMembers(context: SearchContext, className: String): Collection<LuaPsiTypeMember> {
            if (context.isDumb) {
                return listOf()
            }

            return instance.get(className, context.project, context.scope)
        }

        fun processMember(
            context: SearchContext,
            cls: ITyClass,
            fieldName: String,
            searchIndexers: Boolean,
            deep: Boolean,
            indexerSubstitutor: ITySubstitutor?,
            process: ProcessLuaPsiClassMember
        ): Boolean {
            if (context.isDumb) {
                return true
            }

            val memberKey = "*$fieldName"
            val keys = if (searchIndexers) listOf(memberKey, "*[\"${fieldName}\"]") else listOf(memberKey)

            if (!processClassKeys(context, cls, keys, deep, process) || !searchIndexers) {
                return false
            }

            val indexTy = TyPrimitiveLiteral.getTy(TyPrimitiveKind.String, fieldName)
            var inexactIndexerTy: ITy? = null

            processAllIndexers(context, cls, deep) { _, member ->
                val memberIndexerTy = member.guessIndexType(context)
                // 23-06-30 20:58 teddysjwu: 这里会导致self[xxx]这类的优先级太高导致优先跳转，判断一下名字是否相等，不相等降低优先级
                if (member is LuaIndexExpr) {
                    val indexExpr = member as LuaIndexExpr
                    val name = indexExpr.idExpr?.name
                    if (name != fieldName) {
                        return@processAllIndexers true;
                    }
                }
                val candidateIndexerTy = memberIndexerTy?.let {
                    if (indexerSubstitutor != null) {
                        it.substitute(context, indexerSubstitutor)
                    } else {
                        it
                    }
                }

                if (candidateIndexerTy?.contravariantOf(context, indexTy, TyVarianceFlags.STRICT_UNKNOWN) == true) {
                    if (inexactIndexerTy?.contravariantOf(context, candidateIndexerTy, TyVarianceFlags.STRICT_UNKNOWN) != false) {
                        inexactIndexerTy = memberIndexerTy
                    }
                }

                true
            }

            return inexactIndexerTy?.let {
                processClassKeys(context, cls, listOf("*[${it.identifier}]"), deep, process)
            } ?: true
        }

        // TODO: Push this logic back on consumers (assuming it's correct for the use case) and delete the method.
        fun findMethod(context: SearchContext, cls: ITyClass, memberName: String, deep: Boolean = true): LuaTypeMethod<*>? {
            var target: LuaTypeMethod<*>? = null
            processMember(context, cls, memberName, false, deep, null) { _, member ->
                if (member is LuaTypeMethod<*>) {
                    target = member
                    false
                } else {
                    true
                }
            }
            return target
        }

        fun processAllIndexers(context: SearchContext, type: ITyClass, deep: Boolean, process: ProcessLuaPsiClassMember): Boolean {
            if (context.isDumb) {
                return true
            }

            return processClassKeys(context, type, listOf("[]"), deep, process)
        }

        fun processIndexer(
            context: SearchContext,
            type: ITyClass,
            indexTy: ITy,
            exact: Boolean,
            searchMembers: Boolean,
            deep: Boolean,
            indexerSubstitutor: ITySubstitutor?,
            process: ProcessLuaPsiClassMember
        ): Boolean {
            if (context.isDumb) {
                return true
            }

            var exactIndexerFound = false
            val exactIndexerResult = if (searchMembers && indexTy is TyPrimitiveLiteral && indexTy.primitiveKind == TyPrimitiveKind.String) {
                processMember(context, type, indexTy.value, true, deep, indexerSubstitutor) { ownerTy, member ->
                    exactIndexerFound = true
                    process(ownerTy, member)
                }
            } else {
                processClassKeys(context, type, listOf("*[${indexTy.identifier}]"), deep) { ownerTy, member ->
                    exactIndexerFound = true
                    process(ownerTy, member)
                }
            }

            if (exactIndexerFound || exact) {
                return exactIndexerResult
            }

            var inexactIndexerTy: ITy? = null

            processAllIndexers(context, type, deep) { _, member ->
                val memberIndexerTy = member.guessIndexType(context)
                val candidateIndexerTy = memberIndexerTy?.let {
                    if (indexerSubstitutor != null) {
                        it.substitute(context, indexerSubstitutor)
                    } else {
                        it
                    }
                }

                if (candidateIndexerTy?.contravariantOf(context, indexTy, TyVarianceFlags.STRICT_UNKNOWN) == true) {
                    if (inexactIndexerTy?.contravariantOf(context, candidateIndexerTy, TyVarianceFlags.STRICT_UNKNOWN) != false) {
                        inexactIndexerTy = memberIndexerTy
                    }
                }

                true
            }

            return inexactIndexerTy?.let {
                processClassKeys(context, type, listOf("*[${it.identifier}]"), deep, process)
            } ?: false
        }

        fun processAll(context: SearchContext, type: ITyClass, process: ProcessLuaPsiClassMember) {
            if (context.isDumb) {
                return
            }

            if (processKey(context, type, type.className, process)) {
                type.lazyInit(context)
                type.processAlias { aliasedName ->
                    LuaClassIndex.find(context, aliasedName)?.type?.let { aliasedClass ->
                        processKey(context, aliasedClass, aliasedName, process)
                    } ?: true
                }
            }
        }

        fun processNamespaceMember(context: SearchContext, namespace: String, memberName: String, processor: Processor<LuaPsiTypeMember>): Boolean {
            if (context.isDumb) {
                return true
            }

            val key = if (memberName.isNotEmpty())  "$namespace*$memberName" else namespace

            val members = LuaClassMemberIndex.instance.get(key, context.project, context.scope)
            return ContainerUtil.process(members, processor)
        }

        fun indexMemberStub(indexSink: IndexSink, className: String, memberName: String) {
            val nonSelfClassName = getSuffixlessClassName(className)
            indexSink.occurrence(StubKeys.CLASS_MEMBER, nonSelfClassName)
            indexSink.occurrence(StubKeys.CLASS_MEMBER, "$nonSelfClassName*$memberName")
        }

        fun indexIndexerStub(indexSink: IndexSink, className: String, indexTy: ITy) {
            val nonSelfClassName = getSuffixlessClassName(className)
            TyUnion.each(indexTy) {
                if (it is TyPrimitiveLiteral && it.primitiveKind == TyPrimitiveKind.String) {
                    indexMemberStub(indexSink, nonSelfClassName, it.value)
                } else {
                    indexMemberStub(indexSink, nonSelfClassName, "[${it.identifier}]")
                    indexSink.occurrence(StubKeys.CLASS_MEMBER, "$nonSelfClassName[]")
                }
            }
        }
    }
}
