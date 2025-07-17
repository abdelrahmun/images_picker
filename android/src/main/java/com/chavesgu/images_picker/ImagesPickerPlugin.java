package com.chavesgu.images_picker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

import com.luck.picture.lib.PictureSelectionModel;
import com.luck.picture.lib.PictureSelector;
import com.luck.picture.lib.config.PictureMimeType;
import com.luck.picture.lib.entity.LocalMedia;
import com.luck.picture.lib.listener.OnResultCallbackListener;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

/** ImagesPickerPlugin */
public class ImagesPickerPlugin implements
        FlutterPlugin,
        MethodChannel.MethodCallHandler,
        ActivityAware,
        PluginRegistry.RequestPermissionsResultListener {

  private MethodChannel channel;
  private MethodChannel.Result _result;
  private Activity activity;
  private Context context;

  private int WRITE_IMAGE_CODE = 33;
  private int WRITE_VIDEO_CODE = 44;
  private String WRITE_IMAGE_PATH;
  private String WRITE_VIDEO_PATH;
  private String ALBUM_NAME;
  public static String channelName = "chavesgu/images_picker";

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), channelName);
    channel.setMethodCallHandler(this);
    context = flutterPluginBinding.getApplicationContext();
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activity = binding.getActivity();
    binding.addRequestPermissionsResultListener(this);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {}

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    activity = binding.getActivity();
  }

  @Override
  public void onDetachedFromActivity() {
    activity = null;
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    _result = result;
    switch (call.method) {
      case "getPlatformVersion":
        result.success("Android " + Build.VERSION.RELEASE);
        break;
      case "pick":
        handlePick(call);
        break;
      case "openCamera":
        handleCamera(call);
        break;
      case "saveImageToAlbum":
        WRITE_IMAGE_PATH = call.argument("path");
        ALBUM_NAME = call.argument("albumName");
        if (hasPermission()) {
          saveImageToGallery(WRITE_IMAGE_PATH, ALBUM_NAME);
        } else {
          requestPermissions(WRITE_IMAGE_CODE);
        }
        break;
      case "saveVideoToAlbum":
        WRITE_VIDEO_PATH = call.argument("path");
        ALBUM_NAME = call.argument("albumName");
        if (hasPermission()) {
          saveVideoToGallery(WRITE_VIDEO_PATH, ALBUM_NAME);
        } else {
          requestPermissions(WRITE_VIDEO_CODE);
        }
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  private void handlePick(MethodCall call) {
    int count = call.argument("count");
    String pickType = call.argument("pickType");
    double quality = call.argument("quality");
    boolean supportGif = call.argument("gif");
    int maxTime = call.argument("maxTime");
    HashMap<String, Object> cropOption = call.argument("cropOption");
    String language = call.argument("language");

    int chooseType;
    switch (pickType) {
      case "PickType.video":
        chooseType = PictureMimeType.ofVideo();
        break;
      case "PickType.all":
        chooseType = PictureMimeType.ofAll();
        break;
      default:
        chooseType = PictureMimeType.ofImage();
        break;
    }

    PictureSelectionModel model = PictureSelector.create(activity).openGallery(chooseType);
    Utils.setLanguage(model, language);
    Utils.setPhotoSelectOpt(model, count, quality);
    if (cropOption != null) Utils.setCropOpt(model, cropOption);
    model.isGif(supportGif);
    model.videoMaxSecond(maxTime);
    resolveMedias(model);
  }

  private void handleCamera(MethodCall call) {
    String pickType = call.argument("pickType");
    int maxTime = call.argument("maxTime");
    double quality = call.argument("quality");
    HashMap<String, Object> cropOption = call.argument("cropOption");
    String language = call.argument("language");

    int chooseType = "PickType.image".equals(pickType) ? PictureMimeType.ofImage() : PictureMimeType.ofVideo();

    PictureSelectionModel model = PictureSelector.create(activity).openCamera(chooseType);
    model.setOutputCameraPath(context.getExternalCacheDir().getAbsolutePath());
    model.cameraFileName("image_picker_camera_" + UUID.randomUUID() +
            (chooseType == PictureMimeType.ofImage() ? ".jpg" : ".mp4"));
    model.recordVideoSecond(maxTime);
    Utils.setLanguage(model, language);
    Utils.setPhotoSelectOpt(model, 1, quality);
    if (cropOption != null) Utils.setCropOpt(model, cropOption);
    resolveMedias(model);
  }

  private void resolveMedias(PictureSelectionModel model) {
    model.forResult(new OnResultCallbackListener<LocalMedia>() {
      @Override
      public void onResult(final List<LocalMedia> medias) {
        new Thread(() -> {
          final List<Object> resArr = new ArrayList<>();
          for (LocalMedia media : medias) {
            HashMap<String, Object> map = new HashMap<>();
            String path = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? media.getAndroidQToPath()
                    : media.getPath();

            if (media.getMimeType().contains("image")) {
              if (media.isCut()) path = media.getCutPath();
              if (media.isCompressed()) path = media.getCompressPath();
            }

            map.put("path", path);
            map.put("thumbPath", media.getMimeType().contains("image") ? path : createVideoThumb(path));
            map.put("size", getFileSize(path));

            resArr.add(map);
          }

          new Handler(context.getMainLooper()).post(() -> _result.success(resArr));
        }).start();
      }

      @Override
      public void onCancel() {
        _result.success(null);
      }
    });
  }

  private String createVideoThumb(String path) {
    Bitmap bitmap = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.FULL_SCREEN_KIND);
    if (bitmap == null) return null;

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
    try {
      File outputFile = File.createTempFile("image_picker_thumb_" + UUID.randomUUID(), ".jpg", context.getCacheDir());
      FileOutputStream fo = new FileOutputStream(outputFile);
      fo.write(bytes.toByteArray());
      fo.close();
      return outputFile.getAbsolutePath();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  private int getFileSize(String path) {
    File file = new File(path);
    return (int) file.length();
  }

  private void saveImageToGallery(final String path, String albumName) {
    String suffix = path.substring(path.lastIndexOf('.') + 1);
    Bitmap bitmap = BitmapFactory.decodeFile(path);
    boolean status = FileSaver.saveImage(context, bitmap, suffix, albumName);
    _result.success(status);
  }

  private void saveVideoToGallery(String path, String albumName) {
    _result.success(FileSaver.saveVideo(context, path, albumName));
  }

  private boolean hasPermission() {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PERMISSION_GRANTED);
  }

  private void requestPermissions(int code) {
    ActivityCompat.requestPermissions(activity,
            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
            code);
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if (grantResults.length >= 2 &&
            grantResults[0] == PERMISSION_GRANTED &&
            grantResults[1] == PERMISSION_GRANTED) {
      if (requestCode == WRITE_IMAGE_CODE) {
        saveImageToGallery(WRITE_IMAGE_PATH, ALBUM_NAME);
        return true;
      } else if (requestCode == WRITE_VIDEO_CODE) {
        saveVideoToGallery(WRITE_VIDEO_PATH, ALBUM_NAME);
        return true;
      }
    }
    return false;
  }
}
