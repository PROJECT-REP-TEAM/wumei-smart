package com.ruoyi.iot.controller;

import com.alibaba.fastjson.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.file.FileNameLengthLimitExceededException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.iot.domain.Device;
import com.ruoyi.iot.domain.ProductAuthorize;
import com.ruoyi.iot.mapper.ProductAuthorizeMapper;
import com.ruoyi.iot.model.*;
import com.ruoyi.iot.model.ThingsModels.ThingsModelShadow;
import com.ruoyi.iot.mqtt.EmqxService;
import com.ruoyi.iot.mqtt.MqttConfig;
import com.ruoyi.iot.service.IDeviceService;
import com.ruoyi.iot.service.IProductAuthorizeService;
import com.ruoyi.iot.service.IToolService;
import com.ruoyi.iot.service.impl.ThingsModelServiceImpl;
import com.ruoyi.iot.util.AESUtils;
import com.ruoyi.iot.util.VelocityInitializer;
import com.ruoyi.iot.util.VelocityUtils;
import com.ruoyi.system.service.ISysUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.io.IOUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.ruoyi.common.utils.file.FileUploadUtils.getExtension;

/**
 * ????????????Controller
 *
 * @author kerwincui
 * @date 2021-12-16
 */
@Api(tags = "????????????")
@RestController
@RequestMapping("/iot/tool")
public class ToolController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    @Autowired
    private IDeviceService deviceService;

    @Lazy
    @Autowired
    private EmqxService emqxService;

    @Autowired
    private MqttConfig mqttConfig;

    @Autowired
    private IToolService toolService;

    // ????????????
    @Value("${token.secret}")
    private String secret;

    @Autowired
    private RedisCache redisCache;

    /**
     * ????????????
     */
    @ApiOperation("????????????")
    @PostMapping("/register")
    public AjaxResult register(@RequestBody RegisterUserInput user) {
        String msg = toolService.register(user);
        return StringUtils.isEmpty(msg) ? success() : error(msg);
    }

    /**
     * ??????????????????
     */
    @GetMapping("/userList")
    public TableDataInfo list(SysUser user)
    {
        startPage();
        List<SysUser> list = toolService.selectUserList(user);
        return getDataTable(list);
    }

    @ApiOperation("mqtt??????")
    @PostMapping("/mqtt/auth")
    public ResponseEntity mqttAuth(@RequestParam String clientid, @RequestParam String username, @RequestParam String password) throws Exception {
        if (clientid.startsWith("server")) {
            // ?????????????????????????????????????????????
            if (mqttConfig.getusername().equals(username) && mqttConfig.getpassword().equals(password)) {
                log.info("-----------?????????mqtt????????????,clientId:" + clientid + "---------------");
                return ResponseEntity.ok().body("ok");
            } else {
                return toolService.returnUnauthorized(new MqttAuthenticationModel(clientid, username, password), "mqtt????????????????????????????????????????????????");
            }
        } else if (clientid.startsWith("web") || clientid.startsWith("phone")) {
            // web????????????????????????token??????
            String token = password;
            if (StringUtils.isNotEmpty(token) && token.startsWith(Constants.TOKEN_PREFIX)) {
                token = token.replace(Constants.TOKEN_PREFIX, "");
            }
            try {
                Claims claims = Jwts.parser().setSigningKey(secret).parseClaimsJws(token).getBody();
                log.info("-----------?????????/Web???mqtt????????????,clientId:" + clientid + "---------------");
                return ResponseEntity.ok().body("ok");
            } catch (Exception ex) {
                return toolService.returnUnauthorized(new MqttAuthenticationModel(clientid, username, password), ex.getMessage());
            }
        } else {
            // ?????????????????????????????????E?????????????????????S?????????????????????????????????
            String[] clientArray = clientid.split("&");
            if(clientArray.length != 4 || clientArray[0].equals("") || clientArray[1].equals("") || clientArray[2].equals("") || clientArray[3].equals("")){
                return toolService.returnUnauthorized(new MqttAuthenticationModel(clientid, username, password), "??????mqtt?????????Id???????????????????????? & ???????????? & ??????ID & ??????ID");
            }
            String authType = clientArray[0];
            String deviceNumber = clientArray[1];
            Long productId = Long.valueOf(clientArray[2]);
            Long userId = Long.valueOf(clientArray[3]);
            // ??????????????????
            ProductAuthenticateModel model = deviceService.selectProductAuthenticate(new AuthenticateInputModel(deviceNumber, productId));
            if (model == null) {
                return toolService.returnUnauthorized(new MqttAuthenticationModel(clientid, username, password), "???????????????????????????ID??????????????????");
            }
            if (model.getProductStatus() != 2) {
                // ??????????????????????????????1-????????????2-?????????
                return toolService.returnUnauthorized(new MqttAuthenticationModel(clientid, username, password), "?????????????????????????????????????????????");
            }

            if (authType.equals("S")) {
                // ??????????????????
                return toolService.simpleMqttAuthentication(new MqttAuthenticationModel(clientid, username, password, deviceNumber, productId, userId), model);

            } else if (authType.equals("E")) {
                // ??????????????????
                return toolService.encryptAuthentication(new MqttAuthenticationModel(clientid, username, password, deviceNumber, productId, userId), model);
            } else {
                return toolService.returnUnauthorized(new MqttAuthenticationModel(clientid, username, password), "?????????????????????????????????");
            }
        }
    }


    @ApiOperation("mqtt????????????")
    @PostMapping("/mqtt/webhook")
    public void webHookProcess(@RequestBody MqttClientConnectModel model) {
        try {
            System.out.println("webhook:" + model.getAction());
            // ??????????????????web???????????????
            if (model.getClientid().startsWith("server") || model.getClientid().startsWith("web") || model.getClientid().startsWith("phone")) {
                return;
            }
            // ?????????????????????????????????E?????????????????????S?????????????????????????????????
            String[] clientArray = model.getClientid().split("&");
            String authType = clientArray[0];
            String deviceNumber = clientArray[1];
            Long productId = Long.valueOf(clientArray[2]);
            Long userId = Long.valueOf(clientArray[3]);

            Device device = deviceService.selectShortDeviceBySerialNumber(deviceNumber);
            // ???????????????1-????????????2-?????????3-?????????4-?????????
            if (model.getAction().equals("client_disconnected")) {
                device.setStatus(4);
                deviceService.updateDeviceStatusAndLocation(device, "");
                // ??????????????????
                emqxService.publishStatus(device.getProductId(), device.getSerialNumber(), 4, device.getIsShadow());
                // ??????????????????????????????????????????????????????????????????
                emqxService.publishProperty(device.getProductId(), device.getSerialNumber(), null);
                emqxService.publishFunction(device.getProductId(), device.getSerialNumber(), null);
            } else if (model.getAction().equals("client_connected")) {
                device.setStatus(3);
                deviceService.updateDeviceStatusAndLocation(device, model.getIpaddress());
                // ????????????????????????????????????
                if (device.getIsShadow() == 1) {
                    ThingsModelShadow shadow = deviceService.getDeviceShadowThingsModel(device);
                    if (shadow.getProperties().size() > 0) {
                        emqxService.publishProperty(device.getProductId(), device.getSerialNumber(), shadow.getProperties());
                    }
                    if (shadow.getFunctions().size() > 0) {
                        emqxService.publishFunction(device.getProductId(), device.getSerialNumber(), shadow.getFunctions());
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            log.error("???????????????" + ex.getMessage());
        }
    }


    @ApiOperation("??????NTP??????")
    @GetMapping("/ntp")
    public JSONObject ntp(@RequestParam Long deviceSendTime) {
        JSONObject ntpJson = new JSONObject();
        ntpJson.put("deviceSendTime", deviceSendTime);
        ntpJson.put("serverRecvTime", System.currentTimeMillis());
        ntpJson.put("serverSendTime", System.currentTimeMillis());
        return ntpJson;
    }

    /**
     * ????????????
     */
    @PostMapping("/upload")
    @ApiOperation("????????????")
    public AjaxResult uploadFile(MultipartFile file) throws Exception {
        try {
            String filePath = RuoYiConfig.getProfile();
            // ?????????????????????
            int fileNamelength = file.getOriginalFilename().length();
            if (fileNamelength > FileUploadUtils.DEFAULT_FILE_NAME_LENGTH) {
                throw new FileNameLengthLimitExceededException(FileUploadUtils.DEFAULT_FILE_NAME_LENGTH);
            }
            // ??????????????????
            // assertAllowed(file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);

            // ??????????????????????????????
            String fileName = file.getOriginalFilename();
            String extension = getExtension(file);
            //??????????????????
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MMdd-HHmmss");
            fileName = "/iot/" + getLoginUser().getUserId().toString() + "/" + df.format(new Date()) + "." + extension;
            //????????????
            File desc = new File(filePath + File.separator + fileName);
            if (!desc.exists()) {
                if (!desc.getParentFile().exists()) {
                    desc.getParentFile().mkdirs();
                }
            }
            // ????????????
            file.transferTo(desc);

            String url = "/profile" + fileName;
            AjaxResult ajax = AjaxResult.success();
            ajax.put("fileName", url);
            ajax.put("url", url);
            return ajax;
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * ????????????
     */
    @ApiOperation("????????????")
    @GetMapping("/download")
    public void download(String fileName, HttpServletResponse response, HttpServletRequest request) {
        try {
//            if (!FileUtils.checkAllowDownload(fileName)) {
//                throw new Exception(StringUtils.format("????????????({})??????????????????????????? ", fileName));
//            }
            String filePath = RuoYiConfig.getProfile();
            // ????????????
            String downloadPath = filePath + fileName.replace("/profile", "");
            // ????????????
            String downloadName = StringUtils.substringAfterLast(downloadPath, "/");
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            FileUtils.setAttachmentResponseHeader(response, downloadName);
            FileUtils.writeBytes(downloadPath, response.getOutputStream());
        } catch (Exception e) {
            log.error("??????????????????", e);
        }
    }

    /**
     * ??????????????????
     */
    @Log(title = "SDK??????", businessType = BusinessType.GENCODE)
    @GetMapping("/genSdk")
    @ApiOperation("??????SDK")
    public void genSdk(HttpServletResponse response, int deviceChip) throws IOException {
        byte[] data = downloadCode(deviceChip);
        genSdk(response, data);
    }

    /**
     * ??????zip??????
     */
    private void genSdk(HttpServletResponse response, byte[] data) throws IOException {
        response.reset();
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Expose-Headers", "Content-Disposition");
        response.setHeader("Content-Disposition", "attachment; filename=\"ruoyi.zip\"");
        response.addHeader("Content-Length", "" + data.length);
        response.setContentType("application/octet-stream; charset=UTF-8");
        IOUtils.write(data, response.getOutputStream());
    }

    /**
     * ????????????????????????????????????
     *
     * @param deviceChip
     * @return ??????
     */
    public byte[] downloadCode(int deviceChip) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(outputStream);
//        generatorCode(deviceChip, zip);
        IOUtils.closeQuietly(zip);
        return outputStream.toByteArray();
    }

    /**
     * ??????????????????????????????
     */
    private void generatorCode(int deviceChip, ZipOutputStream zip) {
        VelocityInitializer.initVelocity();

        VelocityContext context = VelocityUtils.prepareContext(deviceChip);

        // ??????????????????
        List<String> templates = VelocityUtils.getTemplateList("");
        for (String template : templates) {
            // ????????????
            StringWriter sw = new StringWriter();
            Template tpl = Velocity.getTemplate(template, Constants.UTF8);
            tpl.merge(context, sw);
            try {
                // ?????????zip
                zip.putNextEntry(new ZipEntry(VelocityUtils.getFileName(template)));
                IOUtils.write(sw.toString(), zip, Constants.UTF8);
                IOUtils.closeQuietly(sw);
                zip.flush();
                zip.closeEntry();
            } catch (IOException e) {
                System.out.println("??????????????????");
            }
        }
    }

}
