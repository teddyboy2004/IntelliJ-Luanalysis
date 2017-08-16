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

package com.tang.intellij.lua.stubs.index;

import com.intellij.psi.stubs.StringStubIndexExtension;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.stubs.StubIndexKey;
import com.tang.intellij.lua.psi.LuaGlobal;
import com.tang.intellij.lua.search.SearchContext;
import kotlin.reflect.jvm.internal.impl.utils.SmartList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 *
 * Created by tangzx on 2017/1/16.
 */
public class LuaGlobalIndex extends StringStubIndexExtension<LuaGlobal> {

    public static final StubIndexKey<String, LuaGlobal> KEY = StubIndexKey.createIndexKey("lua.index.global.var");

    private static final LuaGlobalIndex INSTANCE = new LuaGlobalIndex();

    public static LuaGlobalIndex getInstance() {
        return INSTANCE;
    }

    @NotNull
    @Override
    public StubIndexKey<String, LuaGlobal> getKey() {
        return KEY;
    }

    @Nullable
    public static LuaGlobal find(String key, SearchContext context) {
        Collection<LuaGlobal> vars = findAll(key, context);
        if (!vars.isEmpty()) {
            return vars.iterator().next();
        }
        return null;
    }

    @NotNull
    public static Collection<LuaGlobal> findAll(String key, SearchContext context) {
        Collection<LuaGlobal> vars = new SmartList<>();
        if (!context.isDumb()) {
            StubIndex.getInstance().processElements(KEY, key, context.getProject(), context.getScope(), LuaGlobal.class, (s) -> {
                vars.add(s);
                return true;
            });
        }
        return vars;
    }
}