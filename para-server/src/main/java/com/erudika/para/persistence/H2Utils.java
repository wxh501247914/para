/*
 * Copyright 2013-2017 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.persistence;

import com.erudika.para.DestroyListener;
import com.erudika.para.Para;
import com.erudika.para.annotations.Locked;
import com.erudika.para.core.ParaObject;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper utilities for connecting to an H2 database.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class H2Utils {

	private static final Logger logger = LoggerFactory.getLogger(H2Utils.class);

	private static JdbcConnectionPool pool;
	private static Server server;

	private H2Utils() { }

	/**
	 * Returns a connection to H2.
	 * @return a connection instance
	 */
	public static Connection getConnection() {
		try {
			org.h2.Driver.load();

			String dir = Config.getConfigParam("db.dir", "./data");
			String url = "jdbc:h2:" + dir + File.separator + Config.APP_NAME_NS;
			String user = Config.getConfigParam("db.user", Config.APP_NAME_NS);
			String pass = Config.getConfigParam("db.password", "secret");

			String serverParams = Config.getConfigParam("db.tcpServer", "-baseDir " + dir);
			if (server == null && !StringUtils.isBlank(serverParams)) {
				String[] params = StringUtils.split(serverParams, ' ');
				server = Server.createTcpServer(params);
				server.start();
				if (pool == null) {
					pool = JdbcConnectionPool.create(url, user, pass);
				}
				Para.addDestroyListener(new DestroyListener() {
					public void onDestroy() {
						shutdownClient();
					}
				});
			}
			return pool.getConnection();
		} catch (Exception e) {
			logger.error("Failed to start DB server. {}", e.getMessage());
		}
		return null;
	}

	/**
	 * Stops the client and releases resources.
	 * <b>There's no need to call this explicitly!</b>
	 */
	protected static void shutdownClient() {
		if (pool != null) {
			Connection conn = null;
			try {
				conn = pool.getConnection();
				Statement stat = conn.createStatement();
				stat.execute("SHUTDOWN");
				stat.close();
			} catch (Exception e) {
				logger.warn("Failed to shutdown DB server: {}", e.getMessage());
			} finally {
				if (conn != null) {
					try {
						conn.close();
					} catch (Exception e) {
						logger.warn("Failed to close connection to DB server: ", e.getMessage());
					}
				}
			}
		}
		if (server != null) {
			server.stop();
			server = null;
		}
	}


	/**
	 * Checks if the main table exists in the database.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return true if the table exists
	 */
	public static boolean existsTable(String appid) {
		if (StringUtils.isBlank(appid)) {
			return false;
		}
		Connection conn = getConnection();
		try {
			PreparedStatement p = conn.prepareStatement(
					"SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?");
			p.setString(1, getTableNameForAppid(appid));
			ResultSet res = p.executeQuery();
			if (res.next()) {
				String name = res.getString(1);
				p.close();
				return name != null;
			}
		} catch (Exception e) {
			logger.error(null, e);
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException ex) {
					logger.error(null, ex);
				}
			}
		}
		return false;
	}

	/**
	 * Creates a table in H2.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return true if created
	 */
	public static boolean createTable(String appid) {
		if (StringUtils.isBlank(appid) || StringUtils.containsWhitespace(appid) || existsTable(appid)) {
			return false;
		}
		Connection conn = getConnection();
		try {
			String table = getTableNameForAppid(appid);
			Statement s = conn.createStatement();
			String sql = Utils.formatMessage("CREATE TABLE IF NOT EXISTS {0} ({1} NVARCHAR PRIMARY KEY,{2} NVARCHAR,"
					+ "{3} NVARCHAR,{4} NVARCHAR,{5} NVARCHAR,{6} TIMESTAMP,{7} TIMESTAMP,json NVARCHAR)",
					table, Config._ID, Config._TYPE, Config._NAME, Config._PARENTID, Config._CREATORID,
					Config._TIMESTAMP, Config._UPDATED);
			s.execute(sql);
			s.close();
			return true;
		} catch (Exception e) {
			logger.error(null, e);
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException ex) {
					logger.error(null, ex);
				}
			}
		}
		return false;
	}

	/**
	 * Deletes a table.
	 * @param appid id of the {@link com.erudika.para.core.App}
	 * @return true if deleted
	 */
	public static boolean deleteTable(String appid) {
		if (StringUtils.isBlank(appid)) {
			return false;
		}
		Connection conn = getConnection();
		try {
			String table = getTableNameForAppid(appid);
			Statement s = conn.createStatement();
			s.execute("DROP TABLE IF EXISTS " + table);
			s.close();
		} catch (Exception e) {
			logger.error(null, e);
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException ex) {
					logger.error(null, ex);
				}
			}
		}
		return true;
	}

	/**
	 * Returns the table name for a given app id. Table names are usually in the form 'prefix-appid'.
	 * @param appIdentifier app id
	 * @return the table name
	 */
	public static String getTableNameForAppid(String appIdentifier) {
		if (StringUtils.isBlank(appIdentifier)) {
			return null;
		} else {
			return (appIdentifier.equals(Config.APP_NAME_NS) || appIdentifier.startsWith(Config.PARA.concat("_"))) ?
					appIdentifier : Config.PARA + "_" + appIdentifier;
		}
	}

	/**
	 * Converts H2 rows to a {@link ParaObject}s.
	 * @param <P> type of object
	 * @param appid app id
	 * @param ids row ids
	 * @return a list populated Para objects.
	 */
	protected static <P extends ParaObject> Map<String, P> readRows(String appid, List<String> ids) {
		if (StringUtils.isBlank(appid) || ids == null || ids.isEmpty()) {
			return Collections.emptyMap();
		}
		Connection conn = getConnection();
		try {
			Map<String, P> results = new HashMap<String, P>();
			String table = getTableNameForAppid(appid);
			PreparedStatement p = conn.prepareStatement("SELECT json FROM " + table + " WHERE " + Config._ID +
					" IN (" + StringUtils.repeat("?", ",", ids.size()) + ")");
			for (int i = 0; i < ids.size(); i++) {
				p.setString(i + 1, ids.get(i));
			}
			ResultSet res = p.executeQuery();
			while (res.next()) {
				P obj = ParaObjectUtils.fromJSON(res.getString(1));
				if (obj != null) {
					results.put(obj.getId(), obj);
				}
			}
			p.close();
			return results;
		} catch (Exception e) {
			logger.error(null, e);
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException ex) {
					logger.error(null, ex);
				}
			}
		}
		return Collections.emptyMap();
	}

	/**
	 * Converts {@link ParaObject}s to H2 rows and inserts them.
	 * @param <P> type of object
	 * @param appid app id
	 * @param objects list of ParaObjects
	 */
	protected static <P extends ParaObject> void createRows(String appid, List<P> objects) {
		if (StringUtils.isBlank(appid) || objects == null || objects.isEmpty()) {
			return;
		}
		Connection conn = getConnection();
		try {
			String table = getTableNameForAppid(appid);
			PreparedStatement p = conn.prepareStatement("MERGE INTO " + table +	" VALUES (?,?,?,?,?,?,?,?)");

			for (P object : objects) {
				if (StringUtils.isBlank(object.getId())) {
					object.setId(Utils.getNewId());
				}
				if (object.getTimestamp() == null) {
					object.setTimestamp(Utils.timestamp());
				}
				object.setAppid(appid);

				p.setString(1, object.getId());
				p.setString(2, object.getType());
				p.setString(3, object.getName());
				p.setString(4, object.getParentid());
				p.setString(5, object.getCreatorid());
				p.setTimestamp(6, new Timestamp(object.getTimestamp()));
				if (object.getUpdated() == null) {
					p.setNull(7, Types.TIMESTAMP);
				} else {
					p.setTimestamp(7, new Timestamp(object.getUpdated()));
				}
				p.setString(8, ParaObjectUtils.getJsonWriterNoIdent().
						writeValueAsString(ParaObjectUtils.getAnnotatedFields(object, false)));
				p.addBatch();
			}
			p.executeBatch();
			p.close();
		} catch (Exception e) {
			logger.error(null, e);
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException ex) {
					logger.error(null, ex);
				}
			}
		}
	}

	/**
	 * Converts a {@link ParaObject}s to H2 rows and updates them.
	 * @param <P> type of object
	 * @param appid app id
	 * @param objects a list of ParaObjects
	 */
	protected static <P extends ParaObject> void updateRows(String appid, List<P> objects) {
		if (StringUtils.isBlank(appid) || objects == null || objects.isEmpty()) {
			return;
		}
		Connection conn = getConnection();
		try {
			String table = getTableNameForAppid(appid);
			Map<String, P> objectsMap = new HashMap<String, P>(objects.size());
			for (P object : objects) {
				if (object != null && !StringUtils.isBlank(object.getId())) {
					object.setUpdated(Utils.timestamp());
					objectsMap.put(object.getId(), object);
				}
			}

			Map<String, P> existingObjects = readRows(appid, new ArrayList<String>(objectsMap.keySet()));
			String sql = Utils.formatMessage("UPDATE {0} SET {1}=?,{2}=?,{3}=?,{4}=?,{5}=?,{6}=?,json=? "
					+ "WHERE {7} = ?", table, Config._TYPE, Config._NAME, Config._PARENTID, Config._CREATORID,
					Config._TIMESTAMP, Config._UPDATED, Config._ID);

			PreparedStatement p = conn.prepareStatement(sql);

			for (P existingObject : existingObjects.values()) {
				if (existingObject != null) {
					P object = objectsMap.get(existingObject.getId());
					Map<String, Object> data = ParaObjectUtils.getAnnotatedFields(object, false);
					P updated = ParaObjectUtils.setAnnotatedFields(existingObject, data, Locked.class);

					p.setString(1, updated.getType());
					p.setString(2, updated.getName());
					p.setString(3, updated.getParentid());
					p.setString(4, updated.getCreatorid());
					if (updated.getTimestamp() == null) {
						p.setNull(5, Types.TIMESTAMP);
					} else {
						p.setTimestamp(5, new Timestamp(updated.getTimestamp()));
					}
					p.setTimestamp(6, new Timestamp(updated.getUpdated()));
					p.setString(7, ParaObjectUtils.getJsonWriterNoIdent().
							writeValueAsString(ParaObjectUtils.getAnnotatedFields(updated, false)));
					p.setString(8, updated.getId());
					p.addBatch();
				}
			}
			p.executeBatch();
			p.close();
		} catch (Exception e) {
			logger.error(null, e);
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException ex) {
					logger.error(null, ex);
				}
			}
		}
	}

	/**
	 * Deletes a H2 row.
	 * @param <P> type of object
	 * @param appid app id
	 * @param objects a list of ParaObjects
	 */
	protected static <P extends ParaObject> void deleteRows(String appid, List<P> objects) {
		if (StringUtils.isBlank(appid) || objects == null || objects.isEmpty()) {
			return;
		}
		Connection conn = getConnection();
		try {
			String table = getTableNameForAppid(appid);
			PreparedStatement p = conn.prepareStatement("DELETE FROM " + table + " WHERE " + Config._ID +
					" IN (" + StringUtils.repeat("?", ",", objects.size()) + ")");
			for (int i = 0; i < objects.size(); i++) {
				p.setString(i + 1, objects.get(i).getId());
			}
			p.execute();
			p.close();
		} catch (Exception e) {
			logger.error(null, e);
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException ex) {
					logger.error(null, ex);
				}
			}
		}
	}

	/**
	 * Scans the DB one page at a time.
	 * @param <P> type of object
	 * @param appid app id
	 * @param pager a {@link Pager}
	 * @return a list of ParaObjects
	 */
	protected static <P extends ParaObject> List<P> scanRows(String appid, Pager pager) {
		if (StringUtils.isBlank(appid)) {
			return Collections.emptyList();
		}
		if (pager == null) {
			pager = new Pager();
		}
		Connection conn = getConnection();
		try {
			List<P> results = new ArrayList<P>(pager.getLimit());
			String table = getTableNameForAppid(appid);
			int start = pager.getPage() <= 1 ? 0 : (int) (pager.getPage() - 1) * pager.getLimit();
			PreparedStatement p = conn.prepareStatement("SELECT ROWNUM(), json FROM (SELECT json FROM " + table +
					") WHERE ROWNUM() > " + start + " LIMIT " + pager.getLimit());
			ResultSet res = p.executeQuery();
			int i = 0;
			while (res.next()) {
				P obj = ParaObjectUtils.fromJSON(res.getString(2));
				if (obj != null) {
					results.add(obj);
					pager.setLastKey(obj.getId());
					i++;
				}
			}
			pager.setCount(pager.getCount() + i);
			if (pager.getPage() < 2) {
				pager.setPage(2);
			} else {
				pager.setPage(pager.getPage() + 1);
			}
			p.close();
			return results;
		} catch (Exception e) {
			logger.error(null, e);
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException ex) {
					logger.error(null, ex);
				}
			}
		}
		return Collections.emptyList();
	}

}
