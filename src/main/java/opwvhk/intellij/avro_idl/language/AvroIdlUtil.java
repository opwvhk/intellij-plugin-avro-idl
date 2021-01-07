package opwvhk.intellij.avro_idl.language;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import groovy.json.StringEscapeUtils;
import opwvhk.intellij.avro_idl.AvroIdlFileType;
import opwvhk.intellij.avro_idl.psi.*;
import org.apache.avro.Protocol;
import org.apache.avro.Schema;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

public class AvroIdlUtil {
	private static final Logger LOG = Logger.getInstance(AvroIdlUtil.class);
	public static final @NotNull Key<Boolean> IS_ERROR_KEY = Key.create("isError");

	@NotNull
	public static List<NavigationItem> findNavigatableNamedSchemasInProject(Project project) {
		List<NavigationItem> result = new ArrayList<>();

		final PsiManager psiManager = PsiManager.getInstance(project);
		FileTypeIndex.processFiles(AvroIdlFileType.INSTANCE, virtualFile -> {
			Stream.of(virtualFile)
				.flatMap(vf -> readProtocol(psiManager, vf))
				.map(AvroIdlProtocolDeclaration::getProtocolBody)
				.filter(Objects::nonNull)
				.forEach(body -> {
					for (AvroIdlNamedSchemaDeclaration namedSchemaDeclaration : body.getNamedSchemaDeclarationList()) {
						result.add((NavigationItem) namedSchemaDeclaration); // All AvroIdl PSI classes implement NavigationItem
					}
				});
			return true;
		}, GlobalSearchScope.allScope(project));
		return result;
	}

	private static Stream<AvroIdlProtocolDeclaration> readProtocol(PsiManager psiManager, VirtualFile virtualFile) {
		return Stream.ofNullable(psiManager.findFile(virtualFile))
			.flatMap(psiFile -> ifType(psiFile, AvroIdlFile.class))
			.flatMap(AvroIdlUtil::readProtocol);
	}

	private static Stream<AvroIdlProtocolDeclaration> readProtocol(@NotNull AvroIdlFile protocolFile) {
		return Stream.of(protocolFile)
			.flatMap(avroIdlFile -> Stream.of(avroIdlFile.getChildren()))
			.flatMap(child -> ifType(child, AvroIdlProtocolDeclaration.class));
	}

	private static <T> Stream<T> ifType(@Nullable Object object, @NotNull Class<T> type) {
		return type.isInstance(object) ? Stream.of(type.cast(object)) : Stream.empty();
	}

	@NotNull
	public static Stream<LookupElement> findAllSchemaNamesAvailableInProtocol(@NotNull AvroIdlFile protocolFile) {
		return readProtocol(protocolFile).flatMap(protocol -> findAllSchemaNamesAvailableInProtocol(protocol, false, ""));
	}

	@NotNull
	public static Stream<LookupElement> findAllSchemaNamesAvailableInProtocol(@NotNull AvroIdlProtocolDeclaration protocol, boolean errorsOnly,
																			  @NotNull String namespace) {
		return findAllSchemaNamesAvailableInProtocol(protocol, errorsOnly, namespace, new Schema.Parser());
	}
	@NotNull
	private static Stream<LookupElement> findAllSchemaNamesAvailableInProtocol(@NotNull AvroIdlProtocolDeclaration protocol, boolean errorsOnly,
																			  @NotNull String namespace, @NotNull Schema.Parser avroSchemaParser) {
		final Module module = ModuleUtil.findModuleForPsiElement(protocol);
		return Optional.of(protocol)
			.map(AvroIdlProtocolDeclaration::getProtocolBody).stream()
			.flatMap(body -> Stream.concat(
				body.getNamedSchemaDeclarationList().stream()
					.filter(namedSchema -> namedSchema.getFullName() != null)
					.filter(namedSchema -> !errorsOnly || namedSchema.isErrorType())
					.map(namedSchema -> lookupElement(namedSchema, namespace)),
				body.getImportDeclarationList().stream()
					.flatMap(importDeclaration -> findAllSchemaNamesAvailableFromImport(module, importDeclaration, errorsOnly, namespace, avroSchemaParser))
			));
	}

	@NotNull
	private static Stream<LookupElement> findAllSchemaNamesAvailableFromImport(Module module, AvroIdlImportDeclaration importDeclaration, boolean errorsOnly,
																			   @NotNull String namespace, @NotNull Schema.Parser avroSchemaParser) {
		AvroIdlImportType importType = importDeclaration.getImportType();
		final AvroIdlJsonStringLiteral importedFileReferenceElement = importDeclaration.getJsonStringLiteral();
		final String importedFileReference = getJsonString(importedFileReferenceElement);
		if (importType == null || importedFileReference == null) {
			return Stream.empty();
		}

		VirtualFile importedFile = importDeclaration.getContainingFile().getVirtualFile().getParent().findFileByRelativePath(importedFileReference);
		if (importedFile == null) {
			if (module == null) {
				return Stream.empty();
			}
			importedFile = Stream.of(ModuleRootManager.getInstance(module).orderEntries().classes().getRoots())
				.map(root -> root.findFileByRelativePath(importedFileReference))
				.filter(Objects::nonNull)
				.findFirst().orElse(null);
		}
		if (importedFile == null) {
			return Stream.empty();
		}

		if (importType.getFirstChild().getNode().getElementType() == AvroIdlTypes.IDL) {
			final PsiManager psiManager = PsiManager.getInstance(importDeclaration.getProject());
			return readProtocol(psiManager, importedFile).flatMap(
				protocol -> findAllSchemaNamesAvailableInProtocol(protocol, errorsOnly, namespace, avroSchemaParser));
		} else if (importType.getFirstChild().getNode().getElementType() == AvroIdlTypes.PROTOCOL) {
			try (InputStream inputStream = importedFile.getInputStream()) {
				final Protocol protocol = Protocol.parse(inputStream);
				return protocol.getTypes().stream()
					.filter(schema -> !errorsOnly || schema.isError())
					.map(schema -> lookupElement(importedFileReferenceElement, schema, namespace));
			} catch (Exception e) {
				LOG.warn("Failed to read file " + importedFile.getCanonicalPath(), e);
			}
		} else if (importType.getFirstChild().getNode().getElementType() == AvroIdlTypes.SCHEMA) {
			try (InputStream inputStream = importedFile.getInputStream()) {
				avroSchemaParser.parse(inputStream);
				return avroSchemaParser.getTypes().values().stream()
					.filter(schema -> !errorsOnly || schema.isError())
					.map(schema -> lookupElement(importedFileReferenceElement, schema, namespace));
			} catch (Exception e) {
				LOG.warn("Failed to read file " + importedFile.getCanonicalPath(), e);
			}
		}
		// Read failure.
		return Stream.empty();
	}

	@NotNull
	private static LookupElement lookupElement(@NotNull PsiElement psiElement, @NotNull Schema schema, @NotNull String currentNamespace) {
		final String namespace = schema.getNamespace();
		final String schemaName = schema.getName();
		final String schemaFullName = schema.getFullName();
		final LookupElement lookupElement = lookupElement(psiElement, schemaName, schemaFullName, namespace, currentNamespace);
		lookupElement.putUserData(IS_ERROR_KEY, schema.getType() == Schema.Type.RECORD && schema.isError());
		return lookupElement;
	}

	@NotNull
	private static LookupElement lookupElement(@NotNull AvroIdlNamedSchemaDeclaration schemaDeclaration, @NotNull String currentNamespace) {
		final LookupElement lookupElement = lookupElement(schemaDeclaration, requireNonNull(schemaDeclaration.getName()),
			requireNonNull(schemaDeclaration.getFullName()), AvroIdlPsiUtil.getNamespace(schemaDeclaration), currentNamespace);
		lookupElement.putUserData(IS_ERROR_KEY, schemaDeclaration.isErrorType());
		return lookupElement;
	}

	@NotNull
	private static LookupElement lookupElement(@NotNull PsiElement psiElement, @NotNull String schemaName, @NotNull String schemaFullName,
											   @NotNull String namespace, @NotNull String currentNamespace) {
		if (namespace.isEmpty()) {
			return LookupElementBuilder.create(psiElement, schemaName);
		} else if (namespace.equals(currentNamespace)) {
			return LookupElementBuilder.create(psiElement, schemaName).withLookupString(schemaFullName).withTypeText(namespace);
		} else {
			return LookupElementBuilder.create(psiElement, schemaFullName).withLookupString(schemaName).withTypeText(namespace);
		}
	}

	public static @Nullable String getJsonString(@Nullable AvroIdlJsonValue jsonValue) {
		if (jsonValue instanceof AvroIdlJsonStringLiteral) {
			final PsiElement stringLiteral = ((AvroIdlJsonStringLiteral) jsonValue).getStringLiteral();
			String escapedLiteralWithQuotes = stringLiteral.getText();
			String escapedLiteral = escapedLiteralWithQuotes.substring(1, escapedLiteralWithQuotes.length() - 1);
			return StringEscapeUtils.unescapeJavaScript(escapedLiteral);
		}
		return null;
	}

	public static Long getJsonIntValue(@Nullable AvroIdlJsonValue jsonValue) {
		final PsiElement intLiteral = jsonValue == null ? null : jsonValue.getIntLiteral();
		if (intLiteral == null) {
			return null;
		}
		return Long.parseLong(intLiteral.getText());
	}
}
