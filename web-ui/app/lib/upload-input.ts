/** Capture the element synchronously: React clears currentTarget after dispatch. */
export async function uploadInputFiles(
  event: { currentTarget: HTMLInputElement },
  upload: (files: FileList | null) => Promise<void>,
): Promise<void> {
  const input = event.currentTarget;
  const files = input.files;
  try {
    await upload(files);
  } finally {
    // Reset on failure too, so selecting the same file triggers change again.
    input.value = "";
  }
}
