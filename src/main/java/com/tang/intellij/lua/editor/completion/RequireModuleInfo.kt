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

import com.intellij.openapi.vfs.VirtualFile

/**
 * 记录一个模块的 require 信息
 *
 * @param varName     变量名，如 "GameBlackboard"
 * @param requirePath require 路径，如 "game.blackboard.GameBlackboard"
 * @param sourceFile  来源文件（可选，用于展示来源路径）
 */
data class RequireModuleInfo(
    val varName: String,
    val requirePath: String,
    val sourceFile: VirtualFile? = null
)
