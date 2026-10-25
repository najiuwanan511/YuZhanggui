package com.yuzhanggui.service;

import com.yuzhanggui.service.bo.*;

import java.util.List;

public interface DataBackupService {

    List<BackupModuleRespBO> getModules();

    BackupExportRespBO exportData(BackupExportReqBO reqBO);

    BackupImportRespBO importData(BackupImportReqBO reqBO);
}
