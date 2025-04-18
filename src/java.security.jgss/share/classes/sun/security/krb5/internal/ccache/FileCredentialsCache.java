/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * ===========================================================================
 *  (C) Copyright IBM Corp. 1999 All Rights Reserved.
 *
 *  Copyright 1997 The Open Group Research Institute.  All rights reserved.
 * ===========================================================================
 *
 */
package sun.security.krb5.internal.ccache;

import jdk.internal.util.OperatingSystem;
import sun.security.krb5.*;
import sun.security.krb5.internal.*;
import sun.security.util.SecurityProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static sun.security.krb5.internal.Krb5.DEBUG;

/**
 * CredentialsCache stores credentials(tickets, session keys, etc.) in a
 * semi-permanent store
 * for later use by different program.
 *
 * @author Yanni Zhang
 * @author Ram Marti
 */

public class FileCredentialsCache extends CredentialsCache
        implements FileCCacheConstants {
    public int version;
    public Tag tag; // optional
    public PrincipalName primaryPrincipal;
    private Vector<Credentials> credentialsList;

    private final String localCacheName;

    public static synchronized FileCredentialsCache acquireInstance(
                PrincipalName principal, String cache) {
        try {
            String cacheName;
            if (cache == null) {
                cacheName = FileCredentialsCache.getDefaultCacheName();
            } else {
                cacheName = FileCredentialsCache.checkValidation(cache);
            }
            if ((cacheName == null) || !(new File(cacheName)).exists()) {
                // invalid cache name or the file doesn't exist
                return null;
            }
            FileCredentialsCache fcc = new FileCredentialsCache(cacheName);
            if (principal != null) {
                fcc.primaryPrincipal = principal;
            }
            fcc.load();
            return fcc;
        } catch (IOException | KrbException e) {
            // we don't handle it now, instead we return a null at the end.
            if (DEBUG != null) {
                e.printStackTrace(DEBUG.getPrintStream());
            }
        }
        return null;
    }

    public static FileCredentialsCache acquireInstance() {
        return acquireInstance(null, null);
    }

    static synchronized FileCredentialsCache New(PrincipalName principal,
                                                String name) {
        try {
            String cacheName = FileCredentialsCache.checkValidation(name);
            if (cacheName == null) {
                // invalid cache name
                return null;
            }
            FileCredentialsCache fcc = new FileCredentialsCache(cacheName);
            fcc.init(principal);
            return fcc;
        }
        catch (IOException | KrbException e) {
        }
        return null;
    }

    static synchronized FileCredentialsCache New(PrincipalName principal) {
        try {
            String cacheName = FileCredentialsCache.getDefaultCacheName();
            FileCredentialsCache fcc = new FileCredentialsCache(cacheName);
            fcc.init(principal);
            return fcc;
        }
        catch (IOException | KrbException e) {
            if (DEBUG != null) {
                e.printStackTrace(DEBUG.getPrintStream());
            }
        }
        return null;
    }

    private FileCredentialsCache(String cacheName) {
        localCacheName = cacheName;
    }

    @Override
    public String cacheName() {
        return localCacheName;
    }

    synchronized void init(PrincipalName principal)
            throws IOException, KrbException {
        primaryPrincipal = principal;
        try (FileOutputStream fos = new FileOutputStream(localCacheName);
             CCacheOutputStream cos = new CCacheOutputStream(fos)) {
            version = KRB5_FCC_FVNO_3;
            cos.writeHeader(primaryPrincipal, version);
        }
        load();
    }

    synchronized void load() throws IOException, KrbException {
        try (FileInputStream fis = new FileInputStream(localCacheName);
                CCacheInputStream cis = new CCacheInputStream(fis)) {
            version = cis.readVersion();
            if (version == KRB5_FCC_FVNO_4) {
                tag = cis.readTag();
            } else {
                tag = null;
                if (version == KRB5_FCC_FVNO_1 || version == KRB5_FCC_FVNO_2) {
                    cis.setNativeByteOrder();
                }
            }
            PrincipalName p = cis.readPrincipal(version);

            if (primaryPrincipal != null) {
                if (!(primaryPrincipal.match(p))) {
                    throw new IOException("Primary principals don't match.");
                }
            } else {
                primaryPrincipal = p;
            }
            credentialsList = new Vector<>();
            while (cis.available() > 0) {
                Object cred = cis.readCred(version);
                if (cred != null) {
                    if (cred instanceof Credentials) {
                        credentialsList.addElement((Credentials)cred);
                    } else {
                        addConfigEntry((CredentialsCache.ConfigEntry)cred);
                    }
                }
            }
        }
    }


    /**
     * Updates the credentials list. If the specified credentials for the
     * service is new, add it to the list. If there is an entry in the list,
     * replace the old credentials with the new one.
     * @param c the credentials.
     */

    public synchronized void update(Credentials c) {
        if (credentialsList != null) {
            if (credentialsList.isEmpty()) {
                credentialsList.addElement(c);
            } else {
                Credentials tmp;
                boolean matched = false;

                for (int i = 0; i < credentialsList.size(); i++) {
                    tmp = credentialsList.elementAt(i);
                    if (match(c.sname.getNameStrings(),
                              tmp.sname.getNameStrings()) &&
                        ((c.sname.getRealmString()).equalsIgnoreCase(
                                     tmp.sname.getRealmString()))) {
                        matched = true;
                        if (c.endtime.getTime() >= tmp.endtime.getTime()) {
                            if (DEBUG != null) {
                                DEBUG.println(" >>> FileCredentialsCache "
                                         +  "Ticket matched, overwrite "
                                         +  "the old one.");
                            }
                            credentialsList.removeElementAt(i);
                            credentialsList.addElement(c);
                        }
                    }
                }
                if (!matched) {
                    if (DEBUG != null) {
                        DEBUG.println(" >>> FileCredentialsCache Ticket "
                                        +   "not exactly matched, "
                                        +   "add new one into cache.");
                    }

                    credentialsList.addElement(c);
                }
            }
        }
    }

    public synchronized PrincipalName getPrimaryPrincipal() {
        return primaryPrincipal;
    }


    /**
     * Saves the credentials cache file to the disk.
     */
    public synchronized void save() throws IOException, Asn1Exception {
        try (FileOutputStream fos = new FileOutputStream(localCacheName);
                CCacheOutputStream cos = new CCacheOutputStream(fos)) {
            cos.writeHeader(primaryPrincipal, version);
            Credentials[] tmp;
            if ((tmp = getCredsList()) != null) {
                for (int i = 0; i < tmp.length; i++) {
                    cos.addCreds(tmp[i]);
                }
            }
            for (ConfigEntry e : getConfigEntries()) {
                cos.addConfigEntry(primaryPrincipal, e);
            }
        }
    }

    boolean match(String[] s1, String[] s2) {
        if (s1.length != s2.length) {
            return false;
        } else {
            for (int i = 0; i < s1.length; i++) {
                if (!(s1[i].equalsIgnoreCase(s2[i]))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the list of credentials entries in the cache file.
     */
    public synchronized Credentials[] getCredsList() {
        if ((credentialsList == null) || (credentialsList.isEmpty())) {
            return null;
        } else {
            Credentials[] tmp = new Credentials[credentialsList.size()];
            for (int i = 0; i < credentialsList.size(); i++) {
                tmp[i] = credentialsList.elementAt(i);
            }
            return tmp;
        }

    }

    public Credentials getCreds(LoginOptions options, PrincipalName sname) {
        if (options == null) {
            return getCreds(sname);
        } else {
            Credentials[] list = getCredsList();
            if (list == null) {
                return null;
            } else {
                for (int i = 0; i < list.length; i++) {
                    if (sname.match(list[i].sname)) {
                        if (list[i].flags.match(options)) {
                            return list[i];
                        }
                    }
                }
            }
            return null;
        }
    }

    private final List<ConfigEntry> configEntries = new ArrayList<>();

    @Override
    public void addConfigEntry(ConfigEntry e) {
        configEntries.add(e);
    }

    @Override
    public List<ConfigEntry> getConfigEntries() {
        return Collections.unmodifiableList(configEntries);
    }

    /**
     * Gets a credentials for a specified service.
     * @param sname service principal name.
     */
    public Credentials getCreds(PrincipalName sname) {
        Credentials[] list = getCredsList();
        if (list == null) {
            return null;
        } else {
            for (int i = 0; i < list.length; i++) {
                if (sname.match(list[i].sname)) {
                    return list[i];
                }
            }
        }
        return null;
    }

    public sun.security.krb5.Credentials getInitialCreds() {

        Credentials defaultCreds = getDefaultCreds();
        if (defaultCreds == null) {
            return null;
        }
        sun.security.krb5.Credentials tgt = defaultCreds.setKrbCreds();

        CredentialsCache.ConfigEntry entry = getConfigEntry("proxy_impersonator");
        if (entry == null) {
            if (DEBUG != null) {
                DEBUG.println("get normal credential");
            }
            return tgt;
        }

        boolean force;
        String prop = SecurityProperties.getOverridableProperty(
                "jdk.security.krb5.default.initiate.credential");
        if (prop == null) {
            prop = "always-impersonate";
        }
        switch (prop) {
            case "no-impersonate": // never try impersonation
                if (DEBUG != null) {
                    DEBUG.println("get normal credential");
                }
                return tgt;
            case "try-impersonate":
                force = false;
                break;
            case "always-impersonate":
                force = true;
                break;
            default:
                throw new RuntimeException(
                        "Invalid jdk.security.krb5.default.initiate.credential");
        }

        try {
            PrincipalName service = new PrincipalName(
                    new String(entry.getData(), StandardCharsets.UTF_8));
            if (!tgt.getClient().equals(service)) {
                if (DEBUG != null) {
                    DEBUG.println("proxy_impersonator does not match service name");
                }
                return force ? null : tgt;
            }
            PrincipalName client = getPrimaryPrincipal();
            Credentials proxy = null;
            for (Credentials c : getCredsList()) {
                if (c.getClientPrincipal().equals(client)
                        && c.getServicePrincipal().equals(service)) {
                    proxy = c;
                    break;
                }
            }
            if (proxy == null) {
                if (DEBUG != null) {
                    DEBUG.println("Cannot find evidence ticket in ccache");
                }
                return force ? null : tgt;
            }
            if (DEBUG != null) {
                DEBUG.println("Get proxied credential");
            }
            return tgt.setProxy(proxy.setKrbCreds());
        } catch (KrbException e) {
            if (DEBUG != null) {
                DEBUG.println("Impersonation with ccache failed");
            }
            return force ? null : tgt;
        }
    }

    public Credentials getDefaultCreds() {
        Credentials[] list = getCredsList();
        if (list == null) {
            return null;
        } else {
            for (int i = list.length-1; i >= 0; i--) {
                if (list[i].sname.toString().startsWith("krbtgt")) {
                    String[] nameStrings = list[i].sname.getNameStrings();
                    // find the TGT for the current realm krbtgt/realm@realm
                    if (nameStrings[1].equals(list[i].sname.getRealm().toString())) {
                       return list[i];
                    }
                }
            }
        }
        return null;
    }

    /*
     * Returns path name of the credentials cache file.
     * The path name is searched in the following order:
     *
     * 1. KRB5CCNAME (bare file name without FILE:)
     * 2. /tmp/krb5cc_<uid> on unix systems
     * 3. <user.home>/krb5cc_<user.name>
     * 4. <user.home>/krb5cc (if can't get <user.name>)
     */

    public static String getDefaultCacheName() {

        String stdCacheNameComponent = "krb5cc";

        // The env var can start with TYPE:, we only support FILE: here.
        // http://docs.oracle.com/cd/E19082-01/819-2252/6n4i8rtr3/index.html
        String name = System.getenv("KRB5CCNAME");
        if (name != null &&
                (name.length() >= 5) &&
                name.regionMatches(true, 0, "FILE:", 0, 5)) {
            name = name.substring(5);
        }
        if (name != null) {
            if (DEBUG != null) {
                DEBUG.println(">>>KinitOptions cache name is " + name);
            }
            return name;
        }

        /*
         * For Unix platforms we use the default cache name to be
         * /tmp/krb5cc_uid ; for all other platforms  we use
         * {user_home}/krb5cc_{user_name}
         * Please note that for Windows we will use LSA to get
         * the TGT from the default cache even before we come here;
         * however when we create cache we will create a cache under
         * {user_home}/krb5cc_{user_name} for non-Unix platforms including
         * Windows.
         */

        if (!OperatingSystem.isWindows()) {
            long uid = jdk.internal.misc.VM.getuid();
            if (uid != -1) {
                name = File.separator + "tmp" +
                        File.separator + stdCacheNameComponent + "_" + uid;
                if (DEBUG != null) {
                    DEBUG.println(">>>KinitOptions cache name is " +
                            name);
                }
                return name;
            } else {
                if (DEBUG != null) {
                    DEBUG.println("Error in obtaining uid " +
                                        "for Unix platforms " +
                                        "Using user's home directory");
                }
            }
        }

        // we did not get the uid;

        String user_name = System.getProperty("user.name");

        String user_home = System.getProperty("user.home");

        if (user_home == null) {
            user_home = System.getProperty("user.dir");
        }

        if (user_name != null) {
            name = user_home + File.separator  +
                stdCacheNameComponent + "_" + user_name;
        } else {
            name = user_home + File.separator + stdCacheNameComponent;
        }

        if (DEBUG != null) {
            DEBUG.println(">>>KinitOptions cache name is " + name);
        }

        return name;
    }

    public static String checkValidation(String name) {
        String fullname;
        if (name == null) {
            return null;
        }
        try {
            // get full path name
            fullname = (new File(name)).getCanonicalPath();
            File fCheck = new File(fullname);
            if (!(fCheck.exists())) {
                // get absolute directory
                File temp = new File(fCheck.getParent());
                // test if the directory exists
                if (!(temp.isDirectory())) {
                    fullname = null;
                }
            }
        } catch (IOException e) {
            fullname = null; // invalid name
        }
        return fullname;
    }


    private static String exec(String c) {
        StringTokenizer st = new StringTokenizer(c);
        ArrayList<String> v = new ArrayList<>();
        while (st.hasMoreTokens()) {
            v.add(st.nextToken());
        }
        final String[] command = v.toArray(new String[0]);
        try {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(command);
            } catch (IOException e) {
                if (DEBUG != null) {
                    e.printStackTrace(DEBUG.getPrintStream());
                }
            }
            if (p == null) {
                // exception occurred during executing the command
                return null;
            }

            BufferedReader commandResult =
                new BufferedReader
                    (new InputStreamReader(p.getInputStream(), ISO_8859_1));
            String s1;
            if ((command.length == 1) &&
                (command[0].equals("/usr/bin/env"))) {
                while ((s1 = commandResult.readLine()) != null) {
                    if (s1.length() >= 11) {
                        if ((s1.substring(0, 11)).equalsIgnoreCase
                                ("KRB5CCNAME=")) {
                            s1 = s1.substring(11);
                            break;
                        }
                    }
                }
            } else {
                s1 = commandResult.readLine();
            }
            commandResult.close();
            return s1;
        } catch (Exception e) {
            if (DEBUG != null) {
                e.printStackTrace(DEBUG.getPrintStream());
            }
        }
        return null;
    }
}
