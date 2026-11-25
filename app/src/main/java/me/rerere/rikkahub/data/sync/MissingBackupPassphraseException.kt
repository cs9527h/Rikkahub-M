package me.rerere.rikkahub.data.sync

/**
 * 需要口令才能继续（本机没有密钥，或这份备份是 0.9.9 之前的加密包）。
 * 界面捕获它之后会弹口令输入框，而不是当成普通失败。
 */
internal class MissingBackupPassphraseException :
    IllegalStateException("该备份已加密，需要恢复口令")
