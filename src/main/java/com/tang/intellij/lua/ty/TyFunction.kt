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

package com.tang.intellij.lua.ty

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.tang.intellij.lua.comment.psi.LuaDocFunctionTy
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.*
import com.tang.intellij.lua.stubs.index.LuaClassIndex

interface IFunSignature {
    val colonCall: Boolean

    val genericParams: Array<out TyGenericParameter>?

    val returnTy: ITy?
    val params: Array<out LuaParamInfo>?
    val variadicParamTy: ITy?

    val displayName: String
    val paramSignature: String

    fun substitute(context: SearchContext, substitutor: ITySubstitutor): IFunSignature
    fun equals(context: SearchContext, other: IFunSignature, equalityFlags: Int): Boolean
    fun contravariantOf(context: SearchContext, other: IFunSignature, flags: Int): Boolean
}

interface IPsiFunSignature : IFunSignature {
    val psi: PsiElement
}

fun IFunSignature.processParameters(callExpr: LuaCallExpr, processor: (index: Int, param: LuaParamInfo) -> Boolean) {
    val expr = callExpr.expression
    val selfTy = if (expr is LuaIndexExpr) {
        expr.guessParentType(SearchContext.get(expr.project))
    } else null
    processParameters(selfTy, callExpr.isMethodColonCall, processor)
}

fun IFunSignature.processParameters(selfTy: ITy?, colonStyle: Boolean, processor: (index: Int, param: LuaParamInfo) -> Boolean) {
    var index = 0
    var pIndex = 0
    if (colonStyle && !colonCall) {
        pIndex++
    } else if (!colonStyle && colonCall) {
        val pi = LuaParamInfo.createSelf(selfTy)
        if (!processor(index++, pi)) return
    }

    params?.let {
        for (i in pIndex until it.size) {
            if (!processor(index++, it[i])) return
        }
    }
}

fun IFunSignature.processParameters(processor: (index: Int, param: LuaParamInfo) -> Boolean) {
    var index = 0
    if (colonCall)
        index++

    params?.forEach {
        if (!processor(index++, it)) return
    }
}

fun IFunSignature.processParams(thisTy: ITy?, colonStyle: Boolean, processor: (index: Int, param: LuaParamInfo) -> Boolean) {
    var index = 0
    if (colonCall) {
        val pi = LuaParamInfo.createSelf(thisTy)
        if (!processor(index++, pi)) return
    }

    params?.forEach {
        if (!processor(index++, it)) return
    }
}

fun IFunSignature.getFirstParam(thisTy: ITy?, colonStyle: Boolean): LuaParamInfo? {
    var pi: LuaParamInfo? = null
    processParams(thisTy, colonStyle) { _, paramInfo ->
        pi = paramInfo
        false
    }
    return pi
}

fun IFunSignature.getArgTy(index: Int): ITy {
    return params?.getOrNull(index)?.ty ?: Primitives.UNKNOWN
}

//eg. print(...)
fun IFunSignature.hasVarargs(): Boolean {
    return this.variadicParamTy != null
}

fun IFunSignature.isGeneric() = genericParams?.isNotEmpty() == true

abstract class FunSignatureBase(override val colonCall: Boolean,
                                override val params: Array<out LuaParamInfo>?,
                                override val genericParams: Array<out TyGenericParameter>? = null
) : IFunSignature {
    override fun equals(other: Any?): Boolean {
        if (other is IFunSignature) {
            return colonCall == other.colonCall
                    && params?.let { other.params?.contentEquals(it) ?: false } ?: (other.params == null)
                    && genericParams?.let { other.genericParams?.contentEquals(it) ?: false } ?: (other.genericParams == null)
                    && returnTy == other.returnTy
                    && variadicParamTy == other.variadicParamTy
        }
        return false
    }

    override fun equals(context: SearchContext, other: IFunSignature, equalityFlags: Int): Boolean {
        if (colonCall != other.colonCall) {
            return false
        }

        val returnTyEqual = returnTy?.let {
            other.returnTy?.equals(context, it, equalityFlags) ?: false
        } ?: (other.returnTy === null)

        if (!returnTyEqual) {
            return false
        }

        val varargTyEqual = variadicParamTy?.let {
            other.variadicParamTy?.equals(context, it, equalityFlags) ?: false
        } ?: (other.variadicParamTy === null)

        if (!varargTyEqual) {
            return false
        }

        val paramsEqual = params?.let { params ->
            other.params?.let { otherParams ->
                params.size == otherParams.size && otherParams.asSequence().zip(params.asSequence()).all { (param, otherParam) ->
                    param.equals(context, otherParam, equalityFlags)
                }
            } ?: false
        } ?: (other.params == null)

        if (!paramsEqual) {
            return false
        }

        return genericParams?.let { params ->
            other.genericParams?.let { otherParams ->
                params.size == otherParams.size && otherParams.asSequence().zip(params.asSequence()).all { (param, otherParam) ->
                    param.equals(context, otherParam, equalityFlags)
                }
            } ?: false
        } ?: (other.genericParams == null)
    }

    override fun hashCode(): Int {
        var code = if (colonCall) 1 else 0
        params?.forEach {
            code = code * 31 + it.hashCode()
        }
        genericParams?.forEach {
            code = code * 31 + it.hashCode()
        }
        code = code * 31 + (returnTy?.hashCode() ?: 0)
        code = code * 31 + (variadicParamTy?.hashCode() ?: 0)
        return code
    }

    override val displayName: String by lazy {
        let { signature ->
            buildString {
                this.append("fun")
                TyRenderer.SIMPLE.renderSignature(this, signature)
            }
        }
    }

    override val paramSignature: String get() {
        return params?.let {
            val list = arrayOfNulls<String>(it.size)
            for (i in it.indices) {
                val lpi = it[i]
                list[i] = lpi.name
            }
            return "(" + list.joinToString(", ") + ")"
        } ?: ""
    }

    override fun substitute(context: SearchContext, substitutor: ITySubstitutor): IFunSignature {
        var paramsSubstituted = false

        val substitutedParams = params?.map {
            val substitutedParam = it.substitute(context, substitutor)

            if (substitutedParam !== it) {
                paramsSubstituted = true
            }

            substitutedParam
        }

        val substitutedGenericParams = genericParams?.map {
                val substitutedGenericParam = it.substitute(context, substitutor)

                if (substitutedGenericParam !== it) {
                    paramsSubstituted = true
                }

                substitutedGenericParam
            }?.filterIsInstance<TyGenericParameter>()?.toTypedArray()

        var substitutedReturnTy = returnTy?.substitute(context, substitutor)
        val substitutedVarargTy = variadicParamTy?.let { TyMultipleResults.getResult(context, it.substitute(context, substitutor)) }

        if (substitutedReturnTy == returnTy && returnTy is TyGenericParameter && substitutedParams!=null)
        {
            // 特殊处理$返回
            val returnExpr = (returnTy as TyGenericParameter).returnExpr
                if(returnExpr.indexOf("requireField:")!=-1)
                {
                    val matchEntire = Regex(".*requireField:(.*?)\\.(.*)").matchEntire(returnExpr)
                    if (matchEntire!=null)
                    {
                        val paramName = matchEntire.groups[1]!!.value
                        val fieldName = matchEntire.groups[2]!!.value
                        val paramInfo = substitutedParams.find { luaParamInfo -> luaParamInfo.name == paramName}
                        if (paramInfo?.ty is TyTable)
                        {
                            val member = paramInfo.ty.findMember(context, fieldName)
                            val guessType = member?.guessType(context)
                            if (guessType is TyPrimitiveLiteral)
                            {
                                val value = guessType.value
                                val tagClass = LuaClassIndex.find(context, value.replace(Regex(".*[\\.\\\\]"),""))
                                if (tagClass != null)
                                {
                                    substitutedReturnTy = tagClass.type
                                }
                                else
                                {
                                    val file = resolveRequireFile(value, context.project)

                                    if (file != null)
                                    {
                                        var child = PsiTreeUtil.findChildOfType(file, LuaDocTagClass::class.java)
                                        if (child?.type !=null)
                                        {
                                            substitutedReturnTy = child.type
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
//                else
//                {
//                    val language = Language.findLanguageByID("Lua")
//                    val text = "local " + this.varName + " = " + (substitutedTy as TyTable).psi.text + "\nreturn " + returnExpr
//                    var psiFile = PsiFileFactory.getInstance(context.project).createFileFromText("Dummy.lua", language!!, text)
//                    if (psiFile.children.isNotEmpty() && psiFile.lastChild is LuaReturnStat) {
//                        val ret = psiFile.lastChild as LuaReturnStat
//                        var child = ret.exprList?.firstChild
//                        if (child is LuaPsiTypeGuessable)
//                        {
//                            val infer = infer(context, child)
//                            if (infer != null)
//                            {
//                                return infer
//                            }
//                        }
//                    }
//                }
        }


        return if (paramsSubstituted || substitutedReturnTy !== returnTy || substitutedVarargTy !== variadicParamTy) {
            FunSignature(
                colonCall,
                substitutedReturnTy,
                substitutedParams?.toTypedArray(),
                substitutedVarargTy,
                substitutedGenericParams
            )
        } else {
            this
        }
    }

    override fun contravariantOf(context: SearchContext, other: IFunSignature, flags: Int): Boolean {
        params?.let {
            val otherParams = other.params

            if (otherParams == null) {
                return false
            }

            for (i in otherParams.indices) {
                val param = it.getOrNull(i)
                val otherParam = otherParams[i]

                if (param == null) {
                    if (otherParam.optional) {
                        // Allows assignment of fun(str?: string) to fun().

                        // Technically this is unsafe, since fun() is also assignable to fun(num?: number). However, it's useful, and TypeScript allow it, so
                        // we'll follow suit.

                        break
                    } else {
                        return false
                    }
                }

                if (param.optional && !otherParam.optional && otherParam.ty != null) {
                    return false
                }

                val otherParamTy = otherParam.ty ?: Primitives.UNKNOWN

                if (!otherParamTy.contravariantOf(context, param.ty ?: Primitives.UNKNOWN, flags)) {
                    return false
                }
            }
        }

        val otherReturnTy = other.returnTy

        return if (otherReturnTy != null) {
            returnTy?.contravariantOf(context, otherReturnTy, flags) ?: true
        } else returnTy == null
    }
}

open class FunSignature(colonCall: Boolean,
                   override val returnTy: ITy?,
                   params: Array<out LuaParamInfo>?,
                   override val variadicParamTy: ITy? = null,
                   genericParams: Array<out TyGenericParameter>? = null
) : FunSignatureBase(colonCall, params, genericParams) {

    companion object {
        fun serialize(sig: IFunSignature, stream: StubOutputStream) {
            stream.writeBoolean(sig.colonCall)
            stream.writeTyNullable(sig.returnTy)
            stream.writeTyNullable(sig.variadicParamTy)
            stream.writeParamInfoArrayNullable(sig.params)
            stream.writeGenericParamsNullable(sig.genericParams)
        }

        fun deserialize(stream: StubInputStream): FunSignature {
            val colonCall = stream.readBoolean()
            val ret = stream.readTyNullable()
            val varargTy = stream.readTyNullable()
            val params = stream.readParamInfoArrayNullable()
            val genericParams = stream.readGenericParamsNullable()
            return FunSignature(colonCall, ret, params, varargTy, genericParams)
        }
    }
}

class TyDocFunSignature(luaDocFunctionTy: LuaDocFunctionTy, colonCall: Boolean) : FunSignature(
    colonCall,
    luaDocFunctionTy.returnType,
    luaDocFunctionTy.params as? Array<out LuaParamInfo>, // TODO: Remove cast once https://youtrack.jetbrains.com/issue/KT-36399 lands
    luaDocFunctionTy.varargParam,
    luaDocFunctionTy.genericDefList.map { TyGenericParameter(it) }.toTypedArray()
), IPsiFunSignature {
    override val psi = luaDocFunctionTy
}

interface ITyFunction : ITy {
    val mainSignature: IFunSignature
    val signatures: Array<out IFunSignature>
}

abstract class TyFunction : Ty(TyKind.Function), ITyFunction {

    override fun equals(context: SearchContext, other: ITy, equalityFlags: Int): Boolean {
        if (this === other) {
            return true
        }

        val resolvedOther = Ty.resolve(context, other)

        if (resolvedOther is ITyFunction) {
            if (!mainSignature.equals(context, resolvedOther.mainSignature, equalityFlags))
                return false

            return signatures.size == resolvedOther.signatures.size
                    && signatures.asSequence().zip(resolvedOther.signatures.asSequence()).all { (signature, otherSignature) ->
                signature.equals(context, otherSignature, equalityFlags)
            }
        }

        return false
    }

    override fun equals(other: Any?): Boolean {
        if (other is ITyFunction) {
            if (mainSignature != other.mainSignature)
                return false
            return signatures.indices.none { signatures[it] != other.signatures.getOrNull(it) }
        }
        return false
    }

    override fun hashCode(): Int {
        var code = mainSignature.hashCode()
        signatures.forEach {
            code = code * 31 + it.hashCode()
        }
        return code
    }

    override fun contravariantOf(context: SearchContext, other: ITy, varianceFlags: Int): Boolean {
        if (super.contravariantOf(context, other, varianceFlags)) return true

        var matched = false

        processSignatures(context) { sig ->
            if (other == Primitives.FUNCTION) {
                val multipleResults = sig.returnTy as? TyMultipleResults
                matched = multipleResults?.variadic == true && multipleResults.list.size == 1 && multipleResults.list.first().isUnknown
                        && (sig.params?.size ?: 0) == 0 && sig.variadicParamTy?.isUnknown == true
            } else {
                val genericScopeName = sig.genericParams?.firstOrNull()?.scopeName

                other.processSignatures(context) { otherSig ->
                    val otherGenericScopeName = otherSig.genericParams?.firstOrNull()?.scopeName
                    val scopeNames = if (genericScopeName != null) {
                        if (otherGenericScopeName != null) {
                            listOf(genericScopeName, otherGenericScopeName)
                        } else {
                            listOf(genericScopeName)
                        }
                    } else if (otherGenericScopeName != null) {
                        listOf(otherGenericScopeName)
                    } else {
                        listOf()
                    }

                    context.withAbstractGenericScopeNames(scopeNames) {
                        matched = sig.contravariantOf(context, otherSig, varianceFlags)
                        !matched
                    }
                }
            }
            !matched
        }

        return matched
    }

    override fun substitute(context: SearchContext, substitutor: ITySubstitutor): ITy {
        return substitutor.substitute(context, this)
    }

    override fun accept(visitor: ITyVisitor) {
        visitor.visitFun(this)
    }

    override fun processSignatures(context: SearchContext, processor: Processor<IFunSignature>): Boolean {
        // Overloads will always be more (or as) specific as the main signature, so we visit them first.
        for (signature in signatures) {
            if (!processor.process(signature)) {
                return false
            }
        }

        return processor.process(mainSignature)
    }
}

class TyPsiFunction(private val colonCall: Boolean, override val psi: LuaFuncBodyOwner<*>, flags: Int = 0) : TyFunction(), IPsiTy<LuaFuncBodyOwner<*>> {
    init {
        this.flags = flags
        if (colonCall) {
            this.flags = this.flags or TyFlags.SELF_FUNCTION
        }
    }

    override val mainSignature: IFunSignature by lazy {
        val funcBodyOwner = psi

        object : FunSignatureBase(colonCall, funcBodyOwner.params, funcBodyOwner.genericParams), IPsiFunSignature {
            override val psi = funcBodyOwner

            private var cachedReturnTy: ITy? = null

            override val returnTy: ITy? get() {
                if (cachedReturnTy == null) {
                    val context = SearchContext.get(psi.project)
                    val returnTy = context.withMultipleResults { psi.guessReturnType(context) }
                    /**
                     * todo optimize this bug solution
                     * local function test()
                     *      return test
                     * end
                     * -- will crash after type `test`
                     */
                    cachedReturnTy = if (returnTy is TyPsiFunction && returnTy.psi == psi) {
                        null
                    } else {
                        returnTy
                    }
                }

                return cachedReturnTy
            }

            override val variadicParamTy: ITy?
                get() = psi.varargType
        }
    }

    override val signatures: Array<IFunSignature> by lazy {
        psi.overloads
    }
}

class TyDocPsiFunction(override val psi: LuaDocFunctionTy) : TyFunction(), IPsiTy<LuaDocFunctionTy> {
    override val mainSignature: IFunSignature = TyDocFunSignature(psi, false)
    override val signatures: Array<IFunSignature> = emptyArray()
}

class TySerializedFunction(override val mainSignature: IFunSignature,
                           override val signatures: Array<out IFunSignature>,
                           flags: Int = 0) : TyFunction() {
    init {
        this.flags = flags
    }
}

object TyFunctionSerializer : TySerializer<ITyFunction>() {
    override fun deserializeTy(flags: Int, stream: StubInputStream): ITyFunction {
        val mainSig = FunSignature.deserialize(stream)
        val arr = stream.readSignatures()
        return TySerializedFunction(mainSig, arr, flags)
    }

    override fun serializeTy(ty: ITyFunction, stream: StubOutputStream) {
        FunSignature.serialize(ty.mainSignature, stream)
        stream.writeSignatures(ty.signatures)
    }
}
