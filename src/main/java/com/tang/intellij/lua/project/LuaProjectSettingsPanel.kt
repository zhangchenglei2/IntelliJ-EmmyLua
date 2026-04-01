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

package com.tang.intellij.lua.project

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.tang.intellij.lua.editor.completion.RequireModuleIndex
import java.awt.BorderLayout
import java.util.Arrays
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 项目级 EmmyLua 配置面板。
 *
 * 目前包含：Auto Require 检索路径配置。
 * 该配置存储在项目的 emmy.xml 中，每个项目独立配置，不影响其他项目。
 */
class LuaProjectSettingsPanel(private val project: Project) : Configurable {

    private val dataModel = DefaultListModel<String>()
    private val pathList = JBList(dataModel)
    private lateinit var panel: JPanel

    override fun getDisplayName(): String = "EmmyLua"

    override fun createComponent(): JComponent {
        pathList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val listPanel = JPanel(BorderLayout())
        listPanel.add(
            ToolbarDecorator.createDecorator(pathList)
                .setAddAction { addPath() }
                .setRemoveAction { removePath() }
                .createPanel(),
            BorderLayout.CENTER
        )
        listPanel.border = IdeBorderFactory.createTitledBorder("Auto Require 检索路径", false)

        panel = JPanel(BorderLayout())
        panel.add(listPanel, BorderLayout.NORTH)

        // 初始化列表内容
        reset()
        return panel
    }

    private fun addPath() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "选择 Auto Require 检索目录"
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = file.path
        if (!containsPath(path)) {
            dataModel.addElement(path)
        }
    }

    private fun removePath() {
        val selected = pathList.selectedIndex
        if (selected >= 0) {
            dataModel.remove(selected)
        }
    }

    private fun containsPath(path: String): Boolean {
        for (i in 0 until dataModel.size()) {
            if (dataModel.getElementAt(i) == path) return true
        }
        return false
    }

    private fun getCurrentPaths(): Array<String> {
        return Array(dataModel.size()) { dataModel.getElementAt(it) }
    }

    override fun isModified(): Boolean {
        val manager = LuaSourceRootManager.getInstance(project)
        return !Arrays.equals(manager.getAutoRequireSourceRoots(), getCurrentPaths())
    }

    override fun apply() {
        val manager = LuaSourceRootManager.getInstance(project)
        manager.setAutoRequireSourceRoots(getCurrentPaths())
        // 触发索引重建，使新路径立即生效
        RequireModuleIndex.getInstance(project).rebuildIndex()
    }

    override fun reset() {
        dataModel.clear()
        val manager = LuaSourceRootManager.getInstance(project)
        for (path in manager.getAutoRequireSourceRoots()) {
            dataModel.addElement(path)
        }
    }
}
