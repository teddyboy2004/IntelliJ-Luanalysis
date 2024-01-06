/*
 * Copyright (c) 2023
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

package com.tang.intellij.lua.editor.completion;

import com.intellij.codeInsight.completion.CompletionLocation;
import com.intellij.codeInsight.completion.CompletionStatistician;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.psi.statistics.StatisticsInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public class LuaCompletionStatistician extends CompletionStatistician {

    @Override
    public StatisticsInfo serialize(@NotNull final LookupElement element, @NotNull final CompletionLocation location) {
        return forLocation(location).apply(element);
    }

    // 处理函数按使用排序
    @Override
    public @NotNull Function<@NotNull LookupElement, @Nullable StatisticsInfo> forLocation(@NotNull CompletionLocation location) {
        String context = "completion#" + location.getCompletionParameters().getOriginalFile().getLanguage();
        return element -> {
            String lookupString = element instanceof LuaLookupElement ? ((LuaLookupElement) element).getItemText() : element.getLookupString();
            return new StatisticsInfo(context, lookupString);
        };
    }
}
