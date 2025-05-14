package com.github.lilgallon.protobufquicknav.handlers

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.protobuf.lang.psi.PbFile
import com.intellij.protobuf.lang.psi.PbServiceDefinition
import com.intellij.protobuf.lang.psi.PbServiceMethod
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.map2Array
import java.util.Locale.getDefault

class ProtobufGotoDeclarationHandler : GotoDeclarationHandler {

    private fun PbServiceDefinition.getGeneratedClientClass(): PsiClass? {
        val generatedPackage = (containingFile as? PbFile?)
            ?.packageStatement?.packageName?.text?.replace(".rpc", "")

        return generatedPackage?.let {
            findGeneratedClass(
                project = project,
                protoPackage = generatedPackage,
                className = "${name}GrpcClient"
            )
        }
    }

    private fun String.decapitalize(): String = replaceFirstChar { it.lowercase(getDefault()) }

    private fun findGeneratedClass(project: Project, protoPackage: String, className: String): PsiClass? {
        val moduleManager = ModuleManager.getInstance(project)
        val allModules = moduleManager.modules.toList()

        val psiFacade = JavaPsiFacade.getInstance(project)

        for (module in allModules) {
            val scope = GlobalSearchScope.moduleWithDependenciesScope(module)
            val fqn = "$protoPackage.$className"
            val psiClass = psiFacade.findClass(fqn, scope)
            if (psiClass != null) {
                return psiClass
            }
        }

        return null
    }

    override fun getGotoDeclarationTargets(
        element: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        return when (val parentElement = element?.parent) {
            is PbServiceDefinition -> {
                parentElement.getGeneratedClientClass()?.let { arrayOf(it) }
            }

            is PbServiceMethod -> {
                parentElement.parent?.parent
                    ?.let { it as PbServiceDefinition }
                    ?.getGeneratedClientClass()
                    ?.findMethodsByName(parentElement.name?.decapitalize(), false)
                    ?.map2Array { it }
            }

            else -> null
        }
    }
}
