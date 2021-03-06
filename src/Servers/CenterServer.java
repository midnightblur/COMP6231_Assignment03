package Servers;

import Utils.Config;
import Utils.Config.Server_ID;

import javax.jws.WebMethod;
import javax.jws.WebService;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Created by quocminhvu on 2017-05-19.
 */
@WebService
public class CenterServer implements ServerInterface {
    private Map<Character, ArrayList<Record>> recordsMap;
    private int recordID;
    private static final Object lockID = new Object();
    private static final Object lockCount = new Object();
    private Server_ID serverID;
    private int recordsCount;
    private int rmiPort;
    private int udpPort;
    private static final Logger LOGGER = Logger.getLogger(CenterServer.class.getName());

    public CenterServer() {}

    public CenterServer(Server_ID serverID) throws IOException {
        super();
        this.recordsMap = Collections.synchronizedMap(new HashMap<>());
        this.serverID = serverID;
        switch (serverID) {
            case MTL:
                recordID = 0;
                break;
            case LVL:
                recordID = 1;
                break;
            case DDO:
                recordID = 2;
                break;
        }
        this.recordsCount = 0;
        this.rmiPort = Config.getRMIPortByServerID(serverID);
        this.udpPort = Config.getUDPPortByServerID(serverID);

        initiateLogger();
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        startUDPServer();
                    }
                }
        ).start();
        LOGGER.info("Server " + this.serverID + " starts");
    }

    public void setRecordID(int recordID) {
        this.recordID = recordID;
    }

    public void setServerID(Server_ID serverID) {
        this.serverID = serverID;
    }

    public void setRmiPort(int rmiPort) {
        this.rmiPort = rmiPort;
    }

    public void setUdpPort(int udpPort) {
        this.udpPort = udpPort;
    }

    public int getRecordID() {
        synchronized (lockID) {
            return this.recordID;
        }
    }

    public Server_ID getServerID() {
        return this.serverID;
    }

    public int getRmiPort() {
        return this.rmiPort;
    }

    public int getUdpPort() {
        return this.udpPort;
    }
    @WebMethod
    public String createTRecord(String managerID, String firstName, String lastName, String address, String phone, String specialization, String location) {
        char lastNameInitial = Character.toUpperCase(lastName.charAt(0));

        /**
         * Generate the recordID for the new records
         * Lock the recordID variable to prevent multiple threads
         * from creating new records with the same recordID
         */
        String newRecordID;
        synchronized (lockID) {
            newRecordID = String.format(Config.TEACHER_RECORD_FORMAT, recordID);
            recordID += 3;
        }

        // Create new record
        TeacherRecord newRecord = new TeacherRecord(newRecordID, firstName, lastName, address, phone, specialization, location);

        /**
         * Lock the corresponding ArrayList to the LastName's initial character
         * Multiple threads can modify the same ArrayList safely
         * Prevent unpredictable behaviors of ArrayList
         * Ensure recordCount is always true
         * Ensure server logs are updated and reflect server's activities correctly
         */
        ArrayList<Record> recordsList = getRecordsList(lastNameInitial);

        synchronized (recordsList) {
            // Add the new record to the list
            recordsList.add(newRecord);
            synchronized (lockCount) {
                recordsCount++;
            }
            LOGGER.info(String.format(Config.LOG_CREATE_TEACHER_RECORD, managerID, newRecordID, firstName, lastName, address, phone, specialization, location));
        }

        return newRecordID;
    }
    @WebMethod
    public String createSRecord(String managerID, String firstName, String lastName, String coursesRegistered, String status) {
        char lastNameInitial = Character.toUpperCase(lastName.charAt(0));

        /**
         * Generate the recordID for the new records
         * Lock the recordID variable to prevent multiple threads
         * from creating new records with the same recordID
         */
        String newRecordID;
        synchronized (lockID) {
            newRecordID = String.format(Config.STUDENT_RECORD_FORMAT, recordID);
            recordID += 3;
        }

        // Create new record
        StudentRecord newRecord = new StudentRecord(newRecordID, firstName, lastName, coursesRegistered, status, new SimpleDateFormat(Config.DATE_TIME_FORMAT).format(new Date()));

        /**
         * Lock the corresponding ArrayList to the LastName's initial character
         * Multiple threads can modify the same ArrayList safely
         * Prevent unpredictable behaviors of ArrayList
         * Ensure recordCount is always true
         * Ensure server logs are updated and reflect server's activities correctly
         */
        ArrayList<Record> recordsList = getRecordsList(lastNameInitial);

        synchronized (recordsList) {
            // Add the new record to the list
            recordsList.add(newRecord);
            synchronized (lockCount) {
                recordsCount++;
            }
            LOGGER.info(String.format(Config.LOG_CREATE_STUDENT_RECORD, managerID, newRecordID, firstName, lastName, newRecord.getCoursesRegistered(), status));
        }

        return newRecordID;
    }
    @WebMethod
    public String getRecordCounts(String managerID) {
        DatagramSocket socket = null;
        String result = String.format("%s %d", serverID, getRecordsNumber());
        try {
            for (Server_ID id : Server_ID.values()) {
                if (id != serverID) {
                    socket = new DatagramSocket();
                    byte[] request = Config.FUNC_GET_RECORDS_NUMBER.getBytes();
                    InetAddress host = InetAddress.getByName(Config.getHostnameByServerID(id));
                    DatagramPacket sentPacket = new DatagramPacket(request, Config.FUNC_GET_RECORDS_NUMBER.length(), host, Config.getUDPPortByServerID(id));
                    socket.send(sentPacket);

                    byte[] response = new byte[1000];
                    DatagramPacket receivedPacket = new DatagramPacket(response, response.length);
                    socket.receive(receivedPacket);
                    result += String.format(", %s %s", id, new String(receivedPacket.getData()).trim());
                }
            }

            LOGGER.info(String.format(Config.LOG_RECORDS_COUNT, managerID, result));
        } catch (Exception e) {
            LOGGER.severe(e.getMessage());
            System.out.println(e.getMessage());
        } finally {
            if (socket != null)
                socket.close();
        }
        return result;
    }
    @WebMethod
    public boolean editRecord(String managerID, String recordID, String fieldName, String newValue) {
        Record recordFound = locateRecord(recordID);
        char lastNameInitial = Character.toUpperCase(recordFound.getLastName().charAt(0));
        ArrayList<Record> recordsList = getRecordsList(lastNameInitial);

        synchronized (recordsList) {
            for (ArrayList<Record> list : recordsMap.values()) {
                Iterator<Record> iterator = list.iterator();
                while (iterator.hasNext()) {
                    recordFound = iterator.next();
                    System.out.println("Considering recordID = " + recordFound.getRecordID() + " vs " + recordID);
                    if (recordFound.getRecordID().compareTo(recordID) == 0) {
                        System.out.println(recordFound.getRecordID() + " will be edited");
                        if (recordFound.getRecordType().equals(Record.Record_Type.TEACHER)) {
                            TeacherRecord teacherRecord = (TeacherRecord) recordFound;
                            for (TeacherRecord.Mutable_Fields field : TeacherRecord.Mutable_Fields.values()) {
                                if (fieldName.compareTo(field.name()) == 0) try {
                                    Class<?> c = teacherRecord.getClass();
                                    Field f = c.getDeclaredField(fieldName);
                                    f.setAccessible(true);
                                    f.set(teacherRecord, newValue);
                                    f.setAccessible(false);
                                } catch (Exception e) {
                                    LOGGER.severe(e.getMessage());
                                    System.out.println(e.getMessage());
                                    e.printStackTrace();
                                }
                            }
                        } else { // Record_Type == STUDENT
                            StudentRecord studentRecord = (StudentRecord) recordFound;
                            for (StudentRecord.Mutable_Fields field : StudentRecord.Mutable_Fields.values()) {
                                if (fieldName.compareTo(field.name()) == 0) try {
                                    Class<?> c = studentRecord.getClass();
                                    Field whicheverField = c.getDeclaredField(fieldName);
                                    whicheverField.setAccessible(true);
                                    whicheverField.set(studentRecord, newValue);
                                    whicheverField.setAccessible(false);
                                    if (field == StudentRecord.Mutable_Fields.status) {
                                        Field statusDateField = c.getDeclaredField(StudentRecord.Mutable_Fields.statusDate.name());
                                        statusDateField.setAccessible(true);
                                        statusDateField.set(studentRecord, new SimpleDateFormat(Config.DATE_TIME_FORMAT).format(new Date()));
                                        statusDateField.setAccessible(false);
                                    }
                                } catch (Exception e) {
                                    LOGGER.severe(e.getMessage());
                                    System.out.println(e.getMessage());
                                    e.printStackTrace();
                                }
                            }
                        }
                        // Logging
                        LOGGER.info(String.format(Config.LOG_MODIFIED_RECORD_SUCCESS, managerID, recordID, fieldName, newValue));
                        return true;
                    }
                }
            }
            LOGGER.info(String.format(Config.LOG_MODIFIED_RECORD_FAILED, managerID, recordID, fieldName, newValue));
            return false;
        }
    }
    @WebMethod
    public boolean transferRecord(String managerID, String recordID, String remoteCenterServerName) {
        if (remoteCenterServerName.compareTo(this.serverID.name()) != 0) {
            String result = "";
            Record recordFound = locateRecord(recordID);
            char lastNameInitial = Character.toUpperCase(recordFound.getLastName().charAt(0));
            ArrayList<Record> recordsList = getRecordsList(lastNameInitial);

            synchronized (recordsList) {
                // Create the same record on another server via UDP
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    String requestContent = "";
                    Server_ID serverID = Config.Server_ID.valueOf(remoteCenterServerName);
                    InetAddress host = InetAddress.getByName(Config.getHostnameByServerID(serverID));
                    if (recordFound.getRecordType() == Record.Record_Type.TEACHER) {
                        TeacherRecord teacherRecord = (TeacherRecord) recordFound;
                        requestContent = Config.FUNC_TRANSFER_TEACHER_RECORD + "|" + managerID + "|" + recordID + "|" + teacherRecord.getFirstName() + "|" + teacherRecord.getLastName()
                                + "|" + teacherRecord.getAddress() + "|" + teacherRecord.getPhone() + "|" + teacherRecord.getSpecialization() + "|" + teacherRecord.getLocation();
                    } else {
                        StudentRecord studentRecord = (StudentRecord) recordFound;
                        requestContent += Config.FUNC_TRANSFER_STUDENT_RECORD + "|" + managerID + "|" + recordID + "|" + studentRecord.getFirstName() + "|" + studentRecord.getLastName()
                                + "|" + studentRecord.getCoursesRegistered() + "|" + studentRecord.getStatus() + "|" + studentRecord.getStatusDate();
                    }

                    byte[] request = requestContent.getBytes();
                    DatagramPacket sentPacket = new DatagramPacket(request, requestContent.length(), host, Config.getUDPPortByServerID(serverID));
                    socket.send(sentPacket);

                    byte[] response = new byte[1000];
                    DatagramPacket receivedPacket = new DatagramPacket(response, response.length);
                    socket.receive(receivedPacket);
                    result = new String(receivedPacket.getData()).trim();
                } catch (Exception e) {
                    LOGGER.severe(e.getMessage());
                    System.out.println(e.getMessage());
                } finally {
                    if (socket != null)
                        socket.close();
                }

                // If success, delete the record on this server
                if (result.compareTo(recordID) == 0) {
                    synchronized (lockCount) {
                        recordsList.remove(recordFound);
                        recordsCount--;
                        LOGGER.info(String.format(Config.LOG_TRANSFER_RECORD_SUCCESS, managerID, recordID, remoteCenterServerName));
                    }
                    return true;
                }
                else {
                    LOGGER.info(String.format(Config.LOG_TRANSFER_RECORD_FAIL, managerID, recordID, remoteCenterServerName));
                    return false;
                }
            }
        }
        else {
            LOGGER.info(String.format(Config.LOG_TRANSFER_RECORD_FAIL, managerID, recordID, remoteCenterServerName));
            return false;
        }
    }
    @WebMethod
    public String getRecordType(String recordID) {
        if (recordID.length() == 7) {
            String recordPrefix = recordID.substring(0, 2);
            if (recordPrefix.compareTo("TR") == 0)
                return Record.Record_Type.TEACHER.name();
            else if (recordPrefix.compareTo("SR") == 0)
                return Record.Record_Type.STUDENT.name();
            else
                return "";
        }
        else
            return "";
    }
    @WebMethod
    public String printAllRecords() {
        String result = "";
        synchronized (recordsMap) {
            for (ArrayList<Record> recordsList : this.recordsMap.values()) {
                for (int i = 0; i < recordsList.size(); i++) {
                    if (recordsList.get(i).getRecordType() == Record.Record_Type.TEACHER) {
                        TeacherRecord teacherRecord = (TeacherRecord) recordsList.get(i);
                        result += i + " " + String.format(Config.PRINT_TEACHER_RECORD, teacherRecord.getRecordID(), teacherRecord.getFirstName(), teacherRecord.getLastName(), teacherRecord.getAddress(), teacherRecord.getPhone(), teacherRecord.getSpecialization(), teacherRecord.getLocation());
                        result += System.lineSeparator();
                    } else {
                        StudentRecord studentRecord = (StudentRecord) recordsList.get(i);
                        result += i + " " + String.format(Config.PRINT_STUDENT_RECORD, studentRecord.getRecordID(), studentRecord.getFirstName(), studentRecord.getLastName(), studentRecord.getCoursesRegistered(), studentRecord.getStatus(), studentRecord.getStatusDate());
                        result += System.lineSeparator();
                    }
                }
            }
        }
        return result;
    }
    @WebMethod
    public String printRecords(String managerID, String recordID) {
//        Record record = locateRecord(recordID);

//        Map<Character, ArrayList<Record>> map = Collections.synchronizedMap(recordsMap);
        synchronized (recordsMap) {
            for (ArrayList<Record> recordsList : recordsMap.values()) {
                Iterator<Record> iterator = recordsList.iterator();
                while (iterator.hasNext()) {
                    Record recordFound = iterator.next();
                    if (recordFound.getRecordID().compareTo(recordID) == 0) {
                        if (recordFound.getRecordType() == Record.Record_Type.TEACHER) {
                            TeacherRecord teacherRecord = (TeacherRecord) recordFound;
                            return String.format(Config.PRINT_TEACHER_RECORD, recordID, teacherRecord.getFirstName(), teacherRecord.getLastName(), teacherRecord.getAddress(), teacherRecord.getPhone(), teacherRecord.getSpecialization(), teacherRecord.getLocation());
                        } else {
                            StudentRecord studentRecord = (StudentRecord) recordFound;
                            return String.format(Config.PRINT_STUDENT_RECORD, recordID, studentRecord.getFirstName(), studentRecord.getLastName(), studentRecord.getCoursesRegistered(), studentRecord.getStatus(), studentRecord.getStatusDate());
                        }
                    }
                }
            }
        }
        return "";
    }

    private void startUDPServer() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(this.udpPort);
            LOGGER.info(String.format(Config.LOG_UDP_SERVER_START, this.udpPort));

            while (true) {
                // Get the request
                byte[] buffer = new byte[1000];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);

                /**
                 * Each request will be handled by a thread
                 * Making sure that no request would be missed
                 */
                DatagramSocket threadSocket = socket;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String replyStr = "-1";
                        String strRequest = new String(request.getData()).trim();
                        String[] requestComponent = strRequest.split(Config.DELIMITER);
                        switch (requestComponent[0]) {
                            case Config.FUNC_GET_RECORDS_NUMBER:
                                replyStr = Integer.toString(getRecordsNumber());
                                break;
                            case Config.FUNC_TRANSFER_STUDENT_RECORD:
                                replyStr = transferSRecord(requestComponent[1], requestComponent[2], requestComponent[3], requestComponent[4], requestComponent[5], requestComponent[6], requestComponent[7]);
                                break;
                            case Config.FUNC_TRANSFER_TEACHER_RECORD:
                                replyStr = transferTRecord(requestComponent[1], requestComponent[2], requestComponent[3], requestComponent[4], requestComponent[5], requestComponent[6], requestComponent[7], requestComponent[8]);
                                break;
                        }

                        // Reply back
                        DatagramPacket response = new DatagramPacket(replyStr.getBytes(), replyStr.length(), request.getAddress(), request.getPort());
                        try {
                            threadSocket.send(response);
//                        LOGGER.info(String.format(Config.LOG_UDP_METHOD_RESPONSE, calledMethodName, replyStr));
                        } catch (IOException e) {
                            LOGGER.severe(e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }).start();
            }
        } catch (Exception e) {
            LOGGER.severe(e.getMessage());
            System.out.println(e.getMessage());
        } finally {
            if (socket != null) {
                socket.close();
                LOGGER.info(String.format(Config.LOG_UDP_SERVER_STOP, this.udpPort));
            }

        }
    }

    private int getRecordsNumber() {
        /**
         * This function could be called concurrently by many threads
         * when some servers request the number of records of this server at the same time
         * Make sure only one thread can access the shared variable at a time
         */
        synchronized (lockCount) {
            return this.recordsCount;
        }
    }

    private ArrayList<Record> getRecordsList(char lastNameInitial) {
        synchronized (recordsMap) {
            if (recordsMap.containsKey(lastNameInitial))
                return recordsMap.get(lastNameInitial);
            else {
                ArrayList<Record> recordsList = new ArrayList<>();
                recordsMap.put(lastNameInitial, recordsList);
                return recordsList;
            }
        }
    }

    private Record locateRecord(String recordID) {
        Record recordFound = null;
        boolean isRecordFound = false;
        /**
         * Synchronize the whole HashMap to prevent other
         * threads from modifying it when it is being iterated
         */
        synchronized (recordsMap) {
            for (ArrayList<Record> recordsList : this.recordsMap.values()) {
                Iterator<Record> iterator = recordsList.iterator();
                while ((iterator.hasNext()) && (!isRecordFound)) {
                    recordFound = iterator.next();
                    if (recordFound.getRecordID().compareTo(recordID) == 0) {
                        isRecordFound = true;
                        break;
                    }
                }
            }
        }
        if (!isRecordFound)
            recordFound = null;
        return recordFound;
    }

    private void initiateLogger() throws IOException {
        LOGGER.setUseParentHandlers(false);
        FileHandler fileHandler = new FileHandler(String.format(Config.LOG_SERVER_FILENAME, this.serverID));
        LOGGER.addHandler(fileHandler);
        SimpleFormatter formatter = new SimpleFormatter();
        fileHandler.setFormatter(formatter);
    }

    private String transferTRecord(String managerID, String recordID, String firstName, String lastName, String address, String phone, String specialization, String location) {
        char lastNameInitial = Character.toUpperCase(lastName.charAt(0));

        // Create new record
        TeacherRecord newRecord = new TeacherRecord(recordID, firstName, lastName, address, phone, specialization, location);

        /**
         * Lock the corresponding ArrayList to the LastName's initial character
         * Multiple threads can modify the same ArrayList safely
         * Prevent unpredictable behaviors of ArrayList
         * Ensure recordCount is always true
         * Ensure server logs are updated and reflect server's activities correctly
         */
        synchronized (recordsMap) {
            ArrayList<Record> recordsList;
            if (recordsMap.containsKey(lastNameInitial))
                recordsList = recordsMap.get(lastNameInitial);
            else {
                recordsList = new ArrayList<>();
                recordsMap.put(lastNameInitial, recordsList);
            }

            // Add the new record to the list
            recordsList.add(newRecord);
            recordsCount++;
            LOGGER.info(String.format(Config.LOG_TRANSFER_TEACHER_RECORD, managerID, recordID, firstName, lastName, address, phone, specialization, location));
        }

        return recordID;
    }

    private String transferSRecord(String managerID, String recordID, String firstName, String lastName, String coursesRegistered, String status, String statusDate) {
        char lastNameInitial = Character.toUpperCase(lastName.charAt(0));

        // Create new record
        StudentRecord newRecord = null;

        newRecord = new StudentRecord(recordID, firstName, lastName, coursesRegistered, status, statusDate);

        /**
         * Lock the corresponding ArrayList to the LastName's initial character
         * Multiple threads can modify the same ArrayList safely
         * Prevent unpredictable behaviors of ArrayList
         * Ensure recordCount is always true
         * Ensure server logs are updated and reflect server's activities correctly
         */
        synchronized (recordsMap) {
            ArrayList<Record> recordsList;
            if (recordsMap.containsKey(lastNameInitial))
                recordsList =  recordsMap.get(lastNameInitial);
            else {
                recordsList = new ArrayList<>();
                recordsMap.put(lastNameInitial, recordsList);
            }

            // Add the new record to the list
            recordsList.add(newRecord);
            recordsCount++;
            LOGGER.info(String.format(Config.LOG_TRANSFER_STUDENT_RECORD, managerID, recordID, firstName, lastName, newRecord.getCoursesRegistered(), status, statusDate));
        }

        return recordID;
    }
}
