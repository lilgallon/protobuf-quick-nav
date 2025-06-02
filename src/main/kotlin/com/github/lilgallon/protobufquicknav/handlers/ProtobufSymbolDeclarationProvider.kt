package com.github.lilgallon.protobufquicknav.handlers

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.model.psi.PsiSymbolService
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.protobuf.lang.psi.PbFile
import com.intellij.protobuf.lang.psi.PbNamedElement
import com.intellij.protobuf.lang.psi.PbServiceDefinition
import com.intellij.protobuf.lang.psi.ProtoLeafElement
import com.intellij.protobuf.lang.psi.impl.PbMessageDefinitionImpl
import com.intellij.protobuf.lang.psi.impl.PbServiceMethodImpl
import com.intellij.protobuf.lang.psi.impl.PbSimpleFieldImpl
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import java.util.Locale.getDefault

@Suppress("UnstableApiUsage")
class ProtobufSymbolDeclarationProvider : PsiSymbolDeclarationProvider {
    private fun PbNamedElement.getGeneratedClientImplementation(): PsiClass? = getGeneratedClass("GrpcClient")

    private fun PbNamedElement.getGeneratedClientInterface(): PsiClass? = getGeneratedClass("Def")

    private fun PbNamedElement.getGeneratedClass(suffix: String = "", ignoreRpcInPackage: Boolean = true): PsiClass? {
        // com.something.api.rpc.commands becomes com.something.api.commands
        val generatedPackage =
            (containingFile as? PbFile?)
                ?.packageStatement
                ?.packageName
                ?.text
                ?.let {
                    if (ignoreRpcInPackage) {
                        it.replace(".rpc", "")
                    } else {
                        it
                    }
                }

        return generatedPackage?.let {
            findGeneratedClass(
                project = project,
                protoPackage = generatedPackage,
                className = "${name}$suffix",
            )
        }
    }

    private fun String.capitalize(): String = replaceFirstChar { it.uppercase(getDefault()) }

    private fun String.decapitalize(): String = replaceFirstChar { it.lowercase(getDefault()) }

    private fun String.snakeCaseToCamelCase(): String = split("_").joinToString("") { it.capitalize() }.decapitalize()

    private fun PbServiceMethodImpl.getGeneratedMethod(): PsiMethod? =
        // rpc can not have same name, so only one method with same name exist in same service
        (parent.parent as PbServiceDefinition)
            .getGeneratedClientInterface()
            ?.findMethodsByName(name!!.decapitalize(), true)
            ?.firstOrNull()

    private fun PbSimpleFieldImpl.getGeneratedField(): PsiField? =
        (parent.parent as? PbMessageDefinitionImpl?)
            ?.getGeneratedClass()
            ?.findFieldByName(name!!.snakeCaseToCamelCase(), false)

    private fun PbSimpleFieldImpl.getFieldBuilder(prefix: String): PsiMethod? =
        (parent.parent as? PbMessageDefinitionImpl?)
            ?.getGeneratedClass(suffix = "Kt", ignoreRpcInPackage = false)
            ?.findInnerClassByName("Dsl", false)
            ?.findMethodsByName("$prefix${name?.snakeCaseToCamelCase()?.capitalize()}", false)
            ?.firstOrNull()

    private fun PbSimpleFieldImpl.getFieldSetter(): PsiMethod? = getFieldBuilder("set")
    private fun PbSimpleFieldImpl.getFieldGetter(): PsiMethod? = getFieldBuilder("get")

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
            is ProtoLeafElement ->
                when (val parentElement = element.parent) {
                    is PbServiceDefinition -> {
                        listOfNotNull(
                            parentElement.getGeneratedClientImplementation(),
                            parentElement.getGeneratedClientInterface(),
                        ).map { generatedElement -> element.declares(generatedElement) }
                    }

                    is PbServiceMethodImpl -> {
                        listOfNotNull(parentElement.getGeneratedMethod())
                            .map { generatedElement -> element.declares(generatedElement) }
                    }

                    is PbSimpleFieldImpl -> {
                        listOfNotNull(
                            parentElement.getFieldSetter(),
                            parentElement.getFieldGetter(),
                            parentElement.getGeneratedField()
                        )
                            .map { generatedElement -> element.declares(generatedElement) }
                    }

                    else -> emptyList()
                }

            else -> emptyList()
        }
}
