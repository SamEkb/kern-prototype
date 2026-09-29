package com.siams.kern_prototype.data.storage

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * R08: запись файла снимка «всё или ничего». Данные пишутся во временный `<name>.tmp`,
 * сбрасываются на диск (fsync) и только затем переименовываются в [target]. Сбой или
 * остановка процесса оставляют лишь `.tmp`, но не обрезанный JPEG под рабочим именем.
 *
 * @param write пишет данные в поток; false — запись не удалась (например, `Bitmap.compress`).
 */
inline fun writeAtomically(target: File, write: (OutputStream) -> Boolean) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    try {
        val ok = FileOutputStream(tmp).use { out ->
            write(out).also {
                out.flush()
                out.fd.sync()
            }
        }
        check(ok && tmp.length() > 0L) { "Не удалось записать снимок в файл" }
        check(tmp.renameTo(target)) { "Не удалось сохранить файл снимка" }
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
}
