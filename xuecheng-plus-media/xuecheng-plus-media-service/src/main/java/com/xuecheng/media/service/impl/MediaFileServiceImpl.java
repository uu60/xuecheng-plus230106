package com.xuecheng.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.dto.QueryMediaParamsDto;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.dto.UploadFileResultDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileService;
import io.minio.*;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.io.*;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Date;

/**
 * @author Mr.M
 * @version 1.0
 * @description
 * @date 2022/9/10 8:58
 */
@Slf4j
@Service
public class MediaFileServiceImpl implements MediaFileService {

    @Autowired
    MediaFilesMapper mediaFilesMapper;

    @Autowired
    MinioClient minioClient;

    @Autowired
    MediaFileService selfProxy;

    @Autowired
    MediaProcessMapper mediaProcessMapper;

    @Value("${minio.bucket.files}")
    private String filesBucket;
    @Value("${minio.bucket.videofiles}")
    private String videoFilesBucket;

    @Override
    public PageResult<MediaFiles> queryMediaFiles(Long companyId, PageParams pageParams,
                                                  QueryMediaParamsDto queryMediaParamsDto) {
        String filename = queryMediaParamsDto.getFilename();
        String fileType = queryMediaParamsDto.getFileType();
        String auditStatus = queryMediaParamsDto.getAuditStatus();
        Page<MediaFiles> page = new Page<>(pageParams.getPageNo(), pageParams.getPageSize());

        Page<MediaFiles> mediaFilesPage = mediaFilesMapper.selectPage(page,
                new LambdaQueryWrapper<MediaFiles>().eq(MediaFiles::getCompanyId, companyId).like(!StringUtils.isEmpty(filename), MediaFiles::getFilename, filename).eq(!StringUtils.isEmpty(fileType), MediaFiles::getFileType, fileType).eq(!StringUtils.isEmpty(auditStatus), MediaFiles::getAuditStatus, auditStatus));
        return new PageResult<>(mediaFilesPage.getRecords(), mediaFilesPage.getTotal(), pageParams.getPageNo(),
                pageParams.getPageSize());
    }

    @Override
    public UploadFileResultDto uploadFile(Long companyId, UploadFileParamsDto uploadFileParamsDto, byte[] bytes,
                                          String folder, String objectName) {
        if (StringUtils.isEmpty(folder)) {
            // ????????????
            folder = getFileFolder(new Date(), true, true, true);
        }

        if (folder.lastIndexOf("/") != folder.length() - 1) {
            folder += "/";
        }

        // ????????????????????????????????????md5???
        String md5 = DigestUtils.md5DigestAsHex(bytes);
        String filename = uploadFileParamsDto.getFilename();
        if (StringUtils.isEmpty(objectName)) {
            objectName = md5 + filename.substring(filename.lastIndexOf("."));
        }

        // ???????????????
        objectName = folder + objectName;

        addMediaFilesToMinio(bytes, filesBucket, objectName);

        MediaFiles mediaFiles = selfProxy.addMediaFilesToDb(companyId, md5, uploadFileParamsDto, filesBucket,
                objectName);

        UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
        BeanUtils.copyProperties(mediaFiles, uploadFileResultDto);

        return uploadFileResultDto;
    }

    @Override
    @Transactional
    public MediaFiles addMediaFilesToDb(Long companyId, String fileMd5, UploadFileParamsDto uploadFileParamsDto,
                                        String bucketName, String objectName) {
        // ??????????????????
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles == null) {
            // ???????????????
            mediaFiles = new MediaFiles();
            // ????????????
            BeanUtils.copyProperties(uploadFileParamsDto, mediaFiles);
            mediaFiles.setId(fileMd5);
            mediaFiles.setFileId(fileMd5);
            mediaFiles.setCompanyId(companyId);
            String filename = uploadFileParamsDto.getFilename();
            mediaFiles.setFilename(filename);
            mediaFiles.setBucket(bucketName);
            mediaFiles.setFilePath(getFilePathByMd5(fileMd5, filename.contains(".") ?
                    filename.substring(filename.lastIndexOf(".")) : null));
            // ?????????mp4??????????????????URL
            String extension = null;
            if (StringUtils.isNotEmpty(filename) && filename.contains(".")) {
                extension = filename.substring(filename.lastIndexOf("."));
            }
            // ????????????
            String mimeType = getMimeTypeByExtension(extension);
            if (mimeType.contains("image") || mimeType.contains("video")) {
                mediaFiles.setUrl("/" + bucketName + "/" + objectName);
            }
            mediaFiles.setCreateDate(LocalDateTime.now());
            mediaFiles.setStatus("1");
            mediaFiles.setAuditStatus("002003");

            mediaFilesMapper.insert(mediaFiles);

            // ???avi?????????????????????????????????
            if (mimeType.equals("video/x-msvideo")) {
                MediaProcess mediaProcess = new MediaProcess();
                BeanUtils.copyProperties(mediaFiles, mediaProcess);
                mediaProcess.setStatus("1"); // ?????????
                mediaProcessMapper.insert(mediaProcess);
            }
        }
        return mediaFiles;
    }

    @Override
    public RestResponse<Boolean> checkFile(String fileMd5) {
        // ?????????????????????????????????????????????????????????????????????
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles == null) {
            return RestResponse.success(false);
        }
        // ??????????????????????????????
        GetObjectArgs args =
                GetObjectArgs.builder().bucket(mediaFiles.getBucket()).object(mediaFiles.getFilePath()).build();
        try {
            InputStream is = minioClient.getObject(args);
            if (is == null) {
                return RestResponse.success(false);
            }
        } catch (Exception e) {
            return RestResponse.success(false);
        }
        // ???????????????
        return RestResponse.success(true);
    }

    @Override
    public RestResponse<Boolean> checkChunk(String fileMd5, int chunkIndex) {
        // ?????????????????????
        String chunkFilePath = getChunkFilePath(fileMd5, chunkIndex);

        // ??????????????????
        GetObjectArgs args = GetObjectArgs.builder().bucket(videoFilesBucket).object(chunkFilePath).build();
        try {
            InputStream is = minioClient.getObject(args);
            if (is == null) {
                return RestResponse.success(false);
            }
        } catch (Exception e) {
            return RestResponse.success(false);
        }
        // ???????????????
        return RestResponse.success(true);
    }

    private String getChunkFileFolderPath(String fileMd5) {
        return fileMd5.charAt(0) + "/" + fileMd5.charAt(1) + "/" + fileMd5 + "/" + "chunk" + "/";
    }

    private String getChunkFilePath(String fileMd5, int chunkId) {
        return getChunkFileFolderPath(fileMd5) + chunkId;
    }

    @Override
    public RestResponse uploadChunk(String fileMd5, int chunk, byte[] bytes) {
        // ????????????
        if (checkChunk(fileMd5, chunk).getResult()) {
            return RestResponse.success(true);
        }
        String chunkFilePath = getChunkFilePath(fileMd5, chunk);
        try {
            addMediaFilesToMinio(bytes, videoFilesBucket, chunkFilePath);
        } catch (Exception e) {
            return RestResponse.success(false);
        }
        return RestResponse.success(true);
    }

    @Override
    public RestResponse mergechunks(Long companyId, String fileMd5, int chunkTotal,
                                    UploadFileParamsDto uploadFileParamsDto) {
        String filename = uploadFileParamsDto.getFilename();
        String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : null;

//        // ??????minio?????????????????????
//        try {
//            // ??????????????????
//            if (minioClient.getObject(GetObjectArgs.builder().bucket(videoFilesBucket).object(fileMd5.charAt(0) +
//            "/" + fileMd5.charAt(1) + "/" + fileMd5 + "/" + fileMd5 + extension).build()) != null) {
//                return RestResponse.success(true);
//            }
//        } catch (Exception ignored) {
//
//        }

        // ????????????
        File[] chunkFiles = downloadChunkFiles(fileMd5, chunkTotal);

        // ????????????????????????????????????
        File mergeFile = null;
        try {
            try {
                mergeFile = File.createTempFile("merge", extension);
            } catch (IOException e) {
                XueChengPlusException.cast("??????????????????????????????");
                e.printStackTrace();
            }

            // ????????????
            byte[] buf = new byte[1024];
            try (RandomAccessFile rw = new RandomAccessFile(mergeFile, "rw")) {
                Arrays.stream(chunkFiles).forEach(f -> {
                    try (RandomAccessFile rr = new RandomAccessFile(f, "r")) {
                        int len = -1;
                        while ((len = rr.read(buf)) != -1) {
                            rw.write(buf, 0, len);
                        }
                    } catch (Exception e) {
                        XueChengPlusException.cast("????????????????????????");
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // ??????
            try {
                String mergeMd5 = DigestUtils.md5DigestAsHex(Files.newInputStream(mergeFile.toPath()));
                if (!mergeMd5.equals(fileMd5)) {
                    log.debug("??????????????????????????????{}", mergeFile.getAbsolutePath());
                    XueChengPlusException.cast("???????????????????????????");
                }
            } catch (Exception e) {
                // ??????????????????
                ListObjectsArgs args =
                        ListObjectsArgs.builder().bucket(videoFilesBucket).prefix(getChunkFileFolderPath(fileMd5)).build();
                Iterable<Result<Item>> results = minioClient.listObjects(args);
                results.forEach(r -> {
                    try {
                        RemoveObjectArgs args1 =
                                RemoveObjectArgs.builder().bucket(videoFilesBucket).object(r.get().objectName()).build();
                        minioClient.removeObject(args1);
                    } catch (Exception ignored) {

                    }
                });
                XueChengPlusException.cast("????????????????????????");
            }

            // ????????????????????????????????????
            String objectName = getFilePathByMd5(fileMd5, extension);
            addMediaFilesToMinio(mergeFile.getAbsolutePath(), videoFilesBucket, objectName);

            uploadFileParamsDto.setFileSize(mergeFile.length());

            // ?????????????????????
            addMediaFilesToDb(companyId, fileMd5, uploadFileParamsDto, videoFilesBucket, objectName);

            return RestResponse.success(true);
        } finally {
            // ????????????????????????
            if (chunkFiles != null) {
                Arrays.stream(chunkFiles).forEach(file -> {
                    if (file.exists()) {
                        file.delete();
                    }
                });
            }
            if (mergeFile != null && mergeFile.exists()) {
                mergeFile.delete();
            }
        }
    }

    @Override
    public MediaFiles getFileById(String id) {
        MediaFiles mediaFiles = mediaFilesMapper.selectById(id);
        if (mediaFiles == null) {
            XueChengPlusException.cast("???????????????");
        }
        String url = mediaFiles.getUrl();
        if (StringUtils.isEmpty(url)) {
            XueChengPlusException.cast("???????????????????????????????????????");
        }
        return mediaFiles;
    }

    private String getFilePathByMd5(String fileMd5, String fileExt) {
        return fileMd5.charAt(0) + "/" + fileMd5.charAt(1) + "/" + fileMd5 + "/" + fileMd5 + fileExt;
    }

    private File[] downloadChunkFiles(String fileMd5, int chunkTotal) {
        // ??????????????????????????????
        String chunkFileFolderPath = getChunkFileFolderPath(fileMd5);
        // ??????
        File[] chunkFiles = new File[chunkTotal];
        for (int i = 0; i < chunkTotal; i++) {
            String chunkFilePath = chunkFileFolderPath + i;

            File chunkFile = null;
            try {
                chunkFile = File.createTempFile("chunk", null);
            } catch (IOException e) {
                XueChengPlusException.cast("??????????????????????????????");
                e.printStackTrace();
            }

            downloadFileFromMinio(chunkFile, videoFilesBucket, chunkFilePath);
            chunkFiles[i] = chunkFile;
        }
        return chunkFiles;
    }

    @Override
    public void downloadFileFromMinio(File outputFile, String bucketName, String objectName) {
        GetObjectArgs args = GetObjectArgs.builder().bucket(bucketName).object(objectName).build();
        try (InputStream inputStream = minioClient.getObject(args); OutputStream outputStream =
                new FileOutputStream(outputFile)) {
            IOUtils.copy(inputStream, outputStream);
        } catch (Exception e) {
            XueChengPlusException.cast("???minio??????????????????");
            e.printStackTrace();
        }
    }

    // ??????????????????????????????
    private void addMediaFilesToMinio(byte[] bytes, String bucketName, String objectName) {
        String extension = null;
        if (objectName.contains(".")) {
            extension = objectName.substring(objectName.lastIndexOf("."));
        }
        String contentType = getMimeTypeByExtension(objectName);

        // ?????????minio
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        PutObjectArgs putObjectArgs = PutObjectArgs.builder().bucket(bucketName).object(objectName)
                // ??????: ?????????, ????????????, ????????????(-1??????5M, ??????????????????5T, ??????10000)
                .stream(byteArrayInputStream, byteArrayInputStream.available(), -1).contentType(contentType).build();
        try {
            minioClient.putObject(putObjectArgs);
        } catch (Exception e) {
            log.error("????????????????????????????????????{}", e.getMessage());
            e.printStackTrace();
            XueChengPlusException.cast("?????????????????????????????????");
        }
    }

    private String getMimeTypeByExtension(String extension) {
        // ???????????????????????????
        String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        // ???objectName???????????????
        if (StringUtils.isNotEmpty(extension)) {
            ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
            if (extensionMatch != null) {
                contentType = extensionMatch.getMimeType();
            }
        }
        return contentType;
    }

    @Override
    // ??????????????????????????????
    public void addMediaFilesToMinio(String filePath, String bucket, String objectName) {
        try {
            UploadObjectArgs uploadObjectArgs =
                    UploadObjectArgs.builder().bucket(bucket).object(objectName).filename(filePath).build();
            //??????
            minioClient.uploadObject(uploadObjectArgs);
            log.debug("?????????????????????{}", filePath);
        } catch (Exception e) {
            XueChengPlusException.cast("?????????????????????????????????");
        }
    }

    //????????????????????????
    private String getFileFolder(Date date, boolean year, boolean month, boolean day) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        //???????????????????????????
        String dateString = sdf.format(new Date());
        //?????????????????????
        String[] dateStringArray = dateString.split("-");
        StringBuffer folderString = new StringBuffer();
        if (year) {
            folderString.append(dateStringArray[0]);
            folderString.append("/");
        }
        if (month) {
            folderString.append(dateStringArray[1]);
            folderString.append("/");
        }
        if (day) {
            folderString.append(dateStringArray[2]);
            folderString.append("/");
        }
        return folderString.toString();
    }

}
