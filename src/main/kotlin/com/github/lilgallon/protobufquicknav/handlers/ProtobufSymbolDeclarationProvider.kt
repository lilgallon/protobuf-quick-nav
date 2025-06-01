package com.github.lilgallon.protobufquicknav.handlers

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.model.psi.PsiSymbolService
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.protobuf.lang.psi.PbFile
import com.intellij.protobuf.lang.psi.PbServiceDefinition
import com.intellij.protobuf.lang.psi.ProtoLeafElement
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

@Suppress("UnstableApiUsage")
class ProtobufSymbolDeclarationProvider : PsiSymbolDeclarationProvider {
    private fun PbServiceDefinition.getGeneratedClientImplementation(): PsiClass? = getGeneratedClass("GrpcClient")
    private fun PbServiceDefinition.getGeneratedClientInterface(): PsiClass? = getGeneratedClass("Def")

    private fun PbServiceDefinition.getGeneratedClass(suffix: String): PsiClass? {
        // com.something.api.rpc.commands becomes com.something.api.commands
        val generatedPackage = (containingFile as? PbFile?)
            ?.packageStatement
            ?.packageName
            ?.text
            ?.replace(".rpc", "")

        return generatedPackage?.let {
            findGeneratedClass(
                project = project,
                protoPackage = generatedPackage,
                className = "${name}${suffix}",
            )
        }
    }

    private fun findGeneratedClass(
        project: Project,
        protoPackage: String,
        className: String,
    ): PsiClass? {
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

    private fun PsiElement.declares(other: PsiElement): PsiSymbolDeclaration =
        object : PsiSymbolDeclaration {
            override fun getDeclaringElement(): PsiElement = this@declares
            override fun getRangeInDeclaringElement(): TextRange = TextRange(0, this@declares.textLength)
            override fun getSymbol(): Symbol = PsiSymbolService.getInstance().asSymbol(other)
        }

    override fun getDeclarations(
        element: PsiElement,
        offset: Int,
    ): Collection<PsiSymbolDeclaration> =
        when (element) {
            is ProtoLeafElement -> when (val parentElement = element.parent) {
                is PbServiceDefinition -> { // service name
                    listOfNotNull(
                        parentElement.getGeneratedClientImplementation(),
                        parentElement.getGeneratedClientInterface()
                    ).map { generatedElement -> element.declares(generatedElement) }
                }

                else -> emptyList()
            }

            else -> emptyList()
        }
}
