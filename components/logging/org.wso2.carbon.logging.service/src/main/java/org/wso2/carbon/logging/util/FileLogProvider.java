/*
*  Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.logging.util;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.logging.api.ILogFileProvider;
import org.wso2.carbon.logging.config.ServiceConfigManager;
import org.wso2.carbon.logging.config.SyslogConfigManager;
import org.wso2.carbon.logging.config.SyslogConfiguration;
import org.wso2.carbon.logging.service.LogViewerException;
import org.wso2.carbon.logging.service.data.LogInfo;
import org.wso2.carbon.logging.service.data.LogMessage;
import org.wso2.carbon.logging.service.data.LoggingConfig;
import org.wso2.carbon.logging.service.data.SyslogData;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import javax.activation.DataHandler;
import javax.mail.util.ByteArrayDataSource;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class FileLogProvider implements ILogFileProvider {


    private static Log log = LogFactory.getLog(FileLogProvider.class);

    private static final LogMessage[] NO_LOGS_MESSAGE = new LogMessage[] { new LogMessage(
            "NO_LOGS", "INFO") };
    private static final LogInfo[] NO_LOGS_INFO = new LogInfo[] { new LogInfo("NO_LOG_FILES",
            "---", "---") };

    /**
     * Initialize the log provider by reading the property comes with logging configuration file
     * This will be called immediate after create new instance of ILogProvider
     *
     * @param loggingConfig
     */
    @Override
    public void init(LoggingConfig loggingConfig) {

    }

    @Override
    public LogInfo[] getLogInfo(String domain, String serverKey) throws LogViewerException {
        String folderPath = CarbonUtils.getCarbonLogsPath();
        LogInfo log = null;
        if((((domain.equals("") || domain == null) && isSuperTenantUser()) ||
                domain.equalsIgnoreCase(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) &&
                (serverKey == null || serverKey.equals("") || serverKey.equalsIgnoreCase(getCurrentServerName()))) {

            ArrayList<LogInfo> logs = new ArrayList<LogInfo>();
            File folder = new File(folderPath);
            FileFilter fileFilter = new WildcardFileFilter(
                    LoggingConstants.RegexPatterns.LOCAL_CARBON_LOG_PATTERN);
            File[] listOfFiles = folder.listFiles(fileFilter);
            for (File file : listOfFiles) {
                String filename = file.getName();
                String fileDates[] = filename
                        .split(LoggingConstants.RegexPatterns.LOG_FILE_DATE_SEPARATOR);
                String filePath = CarbonUtils.getCarbonLogsPath() + LoggingConstants.URL_SEPARATOR
                        + filename;
                File logfile = new File(filePath);
                if (fileDates.length == 2) {
                    log = new LogInfo(filename, fileDates[1], getFileSize(logfile));
                } else {
                    log = new LogInfo(filename, LoggingConstants.RegexPatterns.CURRENT_LOG,
                            getFileSize(logfile));
                }
                if (log != null) {
                    logs.add(log);
                }
            }
            return getSortedLogInfo(logs.toArray(new LogInfo[logs.size()]));
        } else {
            return null;
        }

    }


    public LogInfo[] getLogsIndex(String tenantDomain, String serviceName) throws Exception {
        return new LogInfo[0];
    }

    @Override
    public DataHandler downloadLogFile(String logFile, String tenantDomain, String serviceName) throws LogViewerException {
        InputStream is;
        int tenantId = LoggingUtil.getTenantIdForDomain(tenantDomain);
        try {
            is = getInputStream(logFile, tenantId, serviceName);
        } catch (LogViewerException e) {
            throw new LogViewerException("Cannot read InputStream from the file " + logFile, e);
        }
        try {
            ByteArrayDataSource bytArrayDS = new ByteArrayDataSource(is, "application/zip");
            DataHandler dataHandler = new DataHandler(bytArrayDS);
            return dataHandler;
        } catch (IOException e) {
            throw new LogViewerException("Cannot read file size from the " + logFile, e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                throw new LogViewerException("Cannot close the input stream " + logFile, e);
            }
        }
    }

    private boolean isSyslogOn() {
        SyslogConfiguration syslogConfig = SyslogConfigManager.loadSyslogConfiguration();
        return syslogConfig.isSyslogOn();
    }


    /**
     * Get Log file index from log collector server.
     *
     * @param tenantId
     * @param serviceName
     * @return LogInfo {Log Name, Date, Size}
     * @throws org.wso2.carbon.logging.service.LogViewerException
     */
    private LogInfo[] getLogInfo(int tenantId, String serviceName) throws LogViewerException {
        InputStream logStream;
        try {
            logStream = getLogDataStream("", tenantId, serviceName);
        } catch (HttpException e) {
            throw new LogViewerException("Cannot establish the connection to the syslog server", e);
        } catch (IOException e) {
            throw new LogViewerException("Cannot find the specified file location to the log file",
                    e);
        } catch (Exception e) {
            throw new LogViewerException("Cannot find the specified file location to the log file",
                    e);
        }
        BufferedReader dataInput = new BufferedReader(new InputStreamReader(logStream));
        String line;
        ArrayList<LogInfo> logs = new ArrayList<LogInfo>();
        Pattern pattern = Pattern.compile(LoggingConstants.RegexPatterns.SYS_LOG_FILE_NAME_PATTERN);
        try {
            while ((line = dataInput.readLine()) != null) {
                String fileNameLinks[] = line
                        .split(LoggingConstants.RegexPatterns.LINK_SEPARATOR_PATTERN);
                String fileDates[] = line
                        .split((LoggingConstants.RegexPatterns.SYSLOG_DATE_SEPARATOR_PATTERN));
                String dates[] = null;
                String sizes[] = null;
                if (fileDates.length == 3) {
                    dates = fileDates[1]
                            .split(LoggingConstants.RegexPatterns.COLUMN_SEPARATOR_PATTERN);
                    sizes = fileDates[2]
                            .split(LoggingConstants.RegexPatterns.COLUMN_SEPARATOR_PATTERN);
                }
                if (fileNameLinks.length == 2) {
                    String logFileName[] = fileNameLinks[1]
                            .split(LoggingConstants.RegexPatterns.GT_PATTARN);
                    Matcher matcher = pattern.matcher(logFileName[0]);
                    if (matcher.find()) {
                        if (logFileName != null && dates != null && sizes != null) {
                            String logName = logFileName[0].replace(
                                    LoggingConstants.RegexPatterns.BACK_SLASH_PATTERN, "");
                            logName = logName.replaceAll("%20", " ");
                            LogInfo log = new LogInfo(logName, dates[0], sizes[0]);
                            logs.add(log);
                        }
                    }
                }
            }
            dataInput.close();
        } catch (IOException e) {
            throw new LogViewerException("Cannot find the specified file location to the log file",
                    e);
        }
        return getSortedLogInfo(logs.toArray(new LogInfo[logs.size()]));
    }

    private LogInfo[] getSortedLogInfo(LogInfo logs[]) {
        int maxLen = logs.length;
        if (maxLen > 0) {
            List<LogInfo> logInfoList = Arrays.asList(logs);
            Collections.sort(logInfoList, new Comparator<Object>() {
                public int compare(Object o1, Object o2) {
                    LogInfo log1 = (LogInfo) o1;
                    LogInfo log2 = (LogInfo) o2;
                    return log1.getLogName().compareToIgnoreCase(log2.getLogName());
                }

            });
            return (LogInfo[]) logInfoList.toArray(new LogInfo[logInfoList.size()]);
        } else {
            return NO_LOGS_INFO;
        }
    }

    private InputStream getLogDataStream(String logFile, int tenantId, String productName)
            throws Exception {
        SyslogData syslogData = getSyslogData();
        String url = "";
        // manager can view all the products tenant log information
        url = getLogsServerURLforTenantService(syslogData.getUrl(), logFile, tenantId, productName);
        String password = syslogData.getPassword();
        String userName = syslogData.getUserName();
        int port = Integer.parseInt(syslogData.getPort());
        String realm = syslogData.getRealm();
        URI uri = new URI(url);
        String host = uri.getHost();
        HttpClient client = new HttpClient();
        client.getState().setCredentials(new AuthScope(host, port, realm),
                new UsernamePasswordCredentials(userName, password));
        GetMethod get = new GetMethod(url);
        get.setDoAuthentication(true);
        client.executeMethod(get);
        return get.getResponseBodyAsStream();
    }

    private SyslogData getSyslogData() throws Exception {
        return LoggingUtil.getSyslogData();
    }

    /*
     * get logs from the local file system.
     */
    private LogInfo[] getLocalLogInfo(String domain, String serverKey) {
        String folderPath = CarbonUtils.getCarbonLogsPath();
        LogInfo log = null;
        if((((domain.equals("") || domain == null) && isSuperTenantUser()) ||
                domain.equalsIgnoreCase(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) &&
                (serverKey == null || serverKey.equals("") || serverKey.equalsIgnoreCase(getCurrentServerName()))) {

            ArrayList<LogInfo> logs = new ArrayList<LogInfo>();
            File folder = new File(folderPath);
            FileFilter fileFilter = new WildcardFileFilter(
                    LoggingConstants.RegexPatterns.LOCAL_CARBON_LOG_PATTERN);
            File[] listOfFiles = folder.listFiles(fileFilter);
            for (File file : listOfFiles) {
                String filename = file.getName();
                String fileDates[] = filename
                        .split(LoggingConstants.RegexPatterns.LOG_FILE_DATE_SEPARATOR);
                String filePath = CarbonUtils.getCarbonLogsPath() + LoggingConstants.URL_SEPARATOR
                        + filename;
                File logfile = new File(filePath);
                if (fileDates.length == 2) {
                    log = new LogInfo(filename, fileDates[1], getFileSize(logfile));
                } else {
                    log = new LogInfo(filename, LoggingConstants.RegexPatterns.CURRENT_LOG,
                            getFileSize(logfile));
                }
                if (log != null) {
                    logs.add(log);
                }
            }
            return getSortedLogInfo(logs.toArray(new LogInfo[logs.size()]));
        } else {
            return null;
        }

    }

    private String getLogsServerURLforTenantService(String syslogServerURL, String logFile,
                                                    int tenantId, String serviceName) throws LogViewerException {
        String serverurl = "";
        String lastChar = String.valueOf(syslogServerURL.charAt(syslogServerURL.length() - 1));
        if (lastChar.equals(LoggingConstants.URL_SEPARATOR)) { // http://my.log.server/logs/stratos/
            syslogServerURL = syslogServerURL.substring(0, syslogServerURL.length() - 1);
        }
        if (isSuperTenantUser()) { // ST can view tenant specific log files.
            if (isManager()) { // manager can view different services log
                // messages.
                if (serviceName != null && serviceName.length() > 0) {
                    serverurl = syslogServerURL + LoggingConstants.URL_SEPARATOR + tenantId
                            + LoggingConstants.URL_SEPARATOR + serviceName
                            + LoggingConstants.URL_SEPARATOR;
                } else {
                    serverurl = syslogServerURL + LoggingConstants.URL_SEPARATOR + tenantId
                            + LoggingConstants.URL_SEPARATOR
                            + LoggingConstants.WSO2_STRATOS_MANAGER
                            + LoggingConstants.URL_SEPARATOR;
                }
                try {
                    if (!isStratosService()) { // stand-alone apps.
                        serverurl = syslogServerURL + LoggingConstants.URL_SEPARATOR + tenantId
                                + LoggingConstants.URL_SEPARATOR
                                + ServerConfiguration.getInstance().getFirstProperty("ServerKey")
                                + LoggingConstants.URL_SEPARATOR;
                    }
                } catch (LogViewerException e) {
                    throw new LogViewerException("Cannot get log ServerURL for Tenant Service", e);
                }
            } else { // for other stratos services can view only their relevant
                // logs.
                serverurl = syslogServerURL + LoggingConstants.URL_SEPARATOR + tenantId
                        + LoggingConstants.URL_SEPARATOR
                        + ServerConfiguration.getInstance().getFirstProperty("ServerKey")
                        + LoggingConstants.URL_SEPARATOR;
            }

        } else { // tenant level logging
            if (isManager()) {
                if (serviceName != null && serviceName.length() > 0) {
                    serverurl = syslogServerURL + LoggingConstants.URL_SEPARATOR
                            + CarbonContext.getCurrentContext().getTenantId()
                            + LoggingConstants.URL_SEPARATOR + serviceName
                            + LoggingConstants.URL_SEPARATOR;
                } else {
                    serverurl = syslogServerURL + LoggingConstants.URL_SEPARATOR
                            + CarbonContext.getCurrentContext().getTenantId()
                            + LoggingConstants.URL_SEPARATOR
                            + LoggingConstants.WSO2_STRATOS_MANAGER
                            + LoggingConstants.URL_SEPARATOR;
                }
            } else {
                serverurl = syslogServerURL + LoggingConstants.URL_SEPARATOR
                        + CarbonContext.getCurrentContext().getTenantId()
                        + LoggingConstants.URL_SEPARATOR
                        + ServerConfiguration.getInstance().getFirstProperty("ServerKey")
                        + LoggingConstants.URL_SEPARATOR;
            }
        }
        serverurl = serverurl.replaceAll("\\s", "%20");
        logFile = logFile.replaceAll("\\s", "%20");
        return serverurl + logFile;
    }

    public boolean isStratosService() throws LogViewerException {
        String serviceName = ServerConfiguration.getInstance().getFirstProperty("ServerKey");
        return ServiceConfigManager.isStratosService(serviceName);
    }

    public boolean isManager() {
        if (LoggingConstants.WSO2_STRATOS_MANAGER.equalsIgnoreCase(ServerConfiguration.getInstance()
                .getFirstProperty("ServerKey"))) {
            return true;
        } else {
            return false;
        }
    }

    public boolean isSuperTenantUser() {
        CarbonContext carbonContext = CarbonContext.getCurrentContext();
        int tenantId = carbonContext.getTenantId();
        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            return true;
        } else {
            return false;
        }
    }

    private String getCurrentServerName() {
        String serverName = ServerConfiguration.getInstance().getFirstProperty("ServerKey");
        return serverName;
    }

    private String getFileSize(File file) {
        long bytes = file.length();
        int unit = 1024;
        if (bytes < unit)
            return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    private InputStream getInputStream(String logFile, int tenantId, String serviceName)
            throws LogViewerException {
        InputStream inputStream;
        try {
            if (isSyslogOn()) {
                inputStream = getLogDataStream(logFile, tenantId, serviceName);
            } else {
                if (isSuperTenantUser()) {
                    inputStream = getLocalInputStream(logFile);
                } else {
                    throw new LogViewerException("Syslog Properties are not properly configured");
                }
            }
            return inputStream;
        } catch (Exception e) {
            throw new LogViewerException("Error getting the file inputstream", e);
        }

    }

    private InputStream getLocalInputStream(String logFile) throws FileNotFoundException {
        logFile = logFile.substring(logFile.lastIndexOf(System.getProperty("file.separator"))+1);
        String fileName = CarbonUtils.getCarbonLogsPath() + LoggingConstants.URL_SEPARATOR
                + logFile;
        InputStream is = new BufferedInputStream(new FileInputStream(fileName));
        return is;
    }
}
