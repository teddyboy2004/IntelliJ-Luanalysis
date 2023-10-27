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

import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.StubIndexKey
import com.tang.intellij.lua.comment.psi.LuaDocTagAlias
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.psi.LuaPsiTypeMember

object StubKeys {
    val CLASS_MEMBER = StubIndexKey.createIndexKey<String, LuaPsiTypeMember>("au.com.glassechidna.luanalysis.class.member")
    val UNKNOWN_MEMBER = StubIndexKey.createIndexKey<String, NavigatablePsiElement>("au.com.glassechidna.luanalysis.unknown.member")
    val SHORT_NAME = StubIndexKey.createIndexKey<String, NavigatablePsiElement>("au.com.glassechidna.luanalysis.short_name")
    val CLASS = StubIndexKey.createIndexKey<String, LuaDocTagClass>("au.com.glassechidna.luanalysis.class")
    val SUPER_CLASS = StubIndexKey.createIndexKey<String, LuaDocTagClass>("au.com.glassechidna.luanalysis.super_class")
    val ALIAS = StubIndexKey.createIndexKey<String, LuaDocTagAlias>("au.com.glassechidna.luanalysis.alias")
}
