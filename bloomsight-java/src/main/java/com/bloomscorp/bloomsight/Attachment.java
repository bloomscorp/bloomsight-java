package com.bloomscorp.bloomsight;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * One file sent with an email, held in memory.
 *
 * <pre>{@code
 * Attachment.of("invoice-A-1042.pdf", pdfBytes);              // type guessed from the name
 * Attachment.of("data.bin", bytes, "application/octet-stream");
 * Attachment.fromPath(Path.of("/srv/reports/november.xlsx"));  // read at once
 * }</pre>
 *
 * <p>The name is cleaned the way blooms.ai cleans it: only the part after the last
 * {@code /} or {@code \} is kept, control characters and double quotes are removed, and
 * the rest is trimmed. {@link #getFilename()} is the cleaned name, exactly what is sent.
 *
 * <p>The limits (1 to 5 files, each 1 byte to 5 MB, 15 MB in all, a name of 1 to 150
 * characters, no blocked types such as {@code .exe}) are checked when the email is
 * sent, before anything is uploaded, so every problem is a {@link BloomsightException}
 * from {@link Bloomsight#sendMail} with one of the codes {@code INVALID_ATTACHMENT},
 * {@code ATTACHMENT_TOO_LARGE}, {@code ATTACHMENTS_TOO_LARGE},
 * {@code TOO_MANY_ATTACHMENTS} or {@code ATTACHMENT_TYPE_BLOCKED}. Creating one only
 * refuses what cannot be a file at all: a {@code null} name or content, or a path that
 * cannot be read; {@link #fromPath(Path)} also refuses a file over 5 MB at once, so a
 * huge file is never read into memory.
 *
 * <p>Immutable: the bytes are copied in and copied out.
 */
public final class Attachment {

	private final String filename;
	private final String contentType;
	private final byte[] content;

	private Attachment(String filename, byte[] content, String contentType) {
		this.filename = Attachments.clean(filename);
		this.content = content.clone();
		String type = contentType == null ? "" : contentType.trim();
		this.contentType = type.isEmpty() ? Attachments.guessType(this.filename) : type;
	}

	/**
	 * A file from bytes, its type guessed from the name (for example {@code .pdf} is
	 * {@code application/pdf}; an unknown one is {@code application/octet-stream}).
	 *
	 * @throws BloomsightException {@code INVALID_ATTACHMENT} when the name or content is {@code null}
	 */
	public static Attachment of(String filename, byte[] content) {
		return of(filename, content, null);
	}

	/**
	 * A file from bytes with its content type, e.g. {@code application/pdf}. A
	 * {@code null} or blank type is guessed from the name.
	 *
	 * @throws BloomsightException {@code INVALID_ATTACHMENT} when the name or content is {@code null}
	 */
	public static Attachment of(String filename, byte[] content, String contentType) {
		if (filename == null) throw new BloomsightException("INVALID_ATTACHMENT", "an attachment needs a file name");
		if (content == null) {
			throw new BloomsightException("INVALID_ATTACHMENT", "attachment '" + Attachments.clean(filename) + "' has no content");
		}
		return new Attachment(filename, content, contentType);
	}

	/**
	 * A file read from disk now, named after the file, its type guessed from the name.
	 *
	 * @throws BloomsightException {@code INVALID_ATTACHMENT} when it cannot be read, or
	 *                             {@code ATTACHMENT_TOO_LARGE} when it is over 5 MB
	 */
	public static Attachment fromPath(Path path) {
		return fromPath(path, null);
	}

	/**
	 * A file read from disk now, named after the file, with its content type. A
	 * {@code null} or blank type is guessed from the name. The message of an error names
	 * the file, never its full path.
	 *
	 * @throws BloomsightException {@code INVALID_ATTACHMENT} when it cannot be read, or
	 *                             {@code ATTACHMENT_TOO_LARGE} when it is over 5 MB
	 */
	public static Attachment fromPath(Path path, String contentType) {
		if (path == null) throw new BloomsightException("INVALID_ATTACHMENT", "an attachment needs a file");
		Path name = path.getFileName();
		if (name == null) throw new BloomsightException("INVALID_ATTACHMENT", "an attachment must be a file");
		String filename = name.toString();
		byte[] content;
		try {
			if (Files.isDirectory(path)) {
				throw new BloomsightException("INVALID_ATTACHMENT", "attachment '" + filename + "' is a folder, not a file");
			}
			// Before reading: a file far over the limit must not be read into memory.
			if (Files.size(path) > Attachments.MAX_FILE_BYTES) throw Attachments.tooLarge(filename);
			content = Files.readAllBytes(path);
		} catch (NoSuchFileException e) {
			throw new BloomsightException("INVALID_ATTACHMENT", "attachment '" + filename + "' was not found");
		} catch (AccessDeniedException e) {
			throw new BloomsightException("INVALID_ATTACHMENT", "attachment '" + filename + "' could not be read: access denied");
		} catch (IOException e) {
			// Not the exception's own message, nor it as the cause: both carry the full path.
			throw new BloomsightException("INVALID_ATTACHMENT",
				"attachment '" + filename + "' could not be read (" + e.getClass().getSimpleName() + ")");
		}
		return new Attachment(filename, content, contentType);
	}

	/** The cleaned name: exactly what is sent. */
	public String getFilename() {
		return filename;
	}

	/** The given content type, or the one guessed from the name. */
	public String getContentType() {
		return contentType;
	}

	/** Size in bytes. */
	public long getSize() {
		return content.length;
	}

	/** A copy of the bytes. */
	public byte[] getContent() {
		return content.clone();
	}

	/** The bytes themselves, for the request body; never handed out. */
	byte[] bytes() {
		return content;
	}

	@Override
	public String toString() {
		return "Attachment[" + filename + ", " + contentType + ", " + content.length + " bytes]";
	}
}
