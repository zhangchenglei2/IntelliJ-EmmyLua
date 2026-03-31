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

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 项目启动后自动预热 require 模块索引。
 *
 * 通过 plugin.xml 中的 <postStartupActivity> 注册，
 * 在项目打开并完成初始化后，在后台任务中构建索引并显示进度条。
 */
class RequireModuleIndexStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        RequireModuleIndex.getInstance(project).warmUp()
    }
}
