package opwvhk.intellij.avro_idl;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class AvroProtocolFileType extends LanguageFileType {
	/**
	 * A shared instance of AvroProtocolFileType.
	 */
	public static final AvroProtocolFileType INSTANCE = new AvroProtocolFileType();

	protected AvroProtocolFileType() {
		super(AvroProtocolLanguage.INSTANCE);
	}

	@Override
	@NotNull
	public String getName() {
		return "Avro Protocol";
	}

	@Override
	@NotNull
	public String getDescription() {
		// False positive: this "description" is used as a name
		//noinspection DialogTitleCapitalization
		return "Apache Avro™ Protocol";
	}

	@Override
	@NotNull
	public String getDefaultExtension() {
		return "avpr";
	}

	@Override
	@Nullable
	public Icon getIcon() {
		return AvroIdlIcons.FILE;
	}
}
