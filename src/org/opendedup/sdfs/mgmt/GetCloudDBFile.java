package org.opendedup.sdfs.mgmt;

import java.io.IOException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.collections.InsertRecord;
import org.opendedup.collections.LongByteArrayMap;
import org.opendedup.collections.LongKeyValue;
import org.opendedup.collections.SparseDataChunk;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.filestore.ChunkData;
import org.opendedup.sdfs.filestore.HashBlobArchive;
import org.opendedup.sdfs.filestore.cloud.FileReplicationService;
import org.opendedup.sdfs.io.HashLocPair;
import org.opendedup.sdfs.notification.SDFSEvent;
import org.opendedup.sdfs.servers.HCServiceProxy;
import org.opendedup.util.FileLock;
import org.opendedup.util.LRUCache;
import org.opendedup.util.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.common.primitives.Longs;

public class GetCloudDBFile implements Runnable {

	LongByteArrayMap ddb = null;
	String guid;
	SDFSEvent fevt = null;
	static LRUCache<String, String> ck = new LRUCache<String, String>(50);
	private static FileLock fl = new FileLock();
	public Element getResult(String guid, String changeid) throws IOException {
		this.guid = guid;
		synchronized (ck) {
			if (ck.containsKey(changeid)) {
				try {
					SDFSLogger.getLog().info(
							"ignoring " + changeid + " " + guid);
					Document doc = XMLUtils.getXMLDoc("clouddbfile");
					Element root = doc.getDocumentElement();
					root.setAttribute("action", "ignored");
					return (Element) root.cloneNode(true);
				} catch (Exception e) {
					throw new IOException(e);
				}
			}
			ck.put(changeid, guid);
		}
		fevt = SDFSEvent.cfEvent(guid);
		ReentrantLock l = fl.getLock(guid);
		l.lock();
		try {
			Document doc = XMLUtils.getXMLDoc("clouddbfile");
			Element root = doc.getDocumentElement();
			fevt.maxCt = 4;
			fevt.curCt = 1;
			fevt.shortMsg = "Downloading ddb at [" + guid + "]";
			ddb = FileReplicationService.getDDB(guid);
			SDFSLogger.getLog().info("size is " +ddb.size() + " guid is " + guid);
			if (ddb.getVersion() < 3)
				throw new IOException(
						"only files version 3 or later can be imported");

			Thread th = new Thread(this);
			th.start();
			return (Element) root.cloneNode(true);
		} catch (IOException e) {

			ddb.vanish(false);
			fevt.endEvent("unable to get " + guid, SDFSEvent.ERROR);
			throw e;
		} catch (Exception e) {
			SDFSLogger.getLog().error("unable to get " + guid, e);
			fevt.endEvent("unable to get " + guid, SDFSEvent.ERROR);
			throw new IOException("request to fetch attributes failed because "
					+ e.toString());

		} finally {
			fl.removeLock(guid);
		}
	}

	private void checkDedupFile(LongByteArrayMap ddb, SDFSEvent fevt)
			throws IOException {
		fevt.shortMsg = "Importing hashes for file";
		SDFSLogger.getLog().info( "Importing hashes for file");
		Set<Long> blks = new HashSet<Long>();
		if (ddb.getVersion() < 3)
			throw new IOException(
					"only files version 3 or later can be imported");
		long k = 0;
		try {
			
			ddb.iterInit();
			for (;;) {
				LongKeyValue kv = ddb.nextKeyValue(Main.refCount);
				
				if (kv == null)
					break;
				SparseDataChunk ck = kv.getValue();
				boolean dirty = false;
				TreeMap<Integer,HashLocPair> al = ck.getFingers();
				for (HashLocPair p : al.values()) {

					ChunkData cm = new ChunkData(
							Longs.fromByteArray(p.hashloc), p.hash);
					InsertRecord ir = HCServiceProxy.getHashesMap().put(cm,
							false);
					if (ir.getInserted())
						blks.add(Longs.fromByteArray(ir.getHashLocs()));
					else {
						if (!Arrays.equals(p.hashloc, ir.getHashLocs())) {
							p.hashloc = ir.getHashLocs();
							blks.add(Longs.fromByteArray(ir.getHashLocs()));
							dirty = true;
						}
					}
				}
				if (dirty)
					ddb.put(kv.getKey(), ck);
				k++;
			}
			for (Long l : blks) {
				boolean inserted = false;
				int trs = 0;
				while (!inserted) {
					try {
						HashBlobArchive.claimBlock(l);
						inserted = true;

					} catch (Exception e) {
						trs++;

						if (trs > 100)
							throw e;
						else
							Thread.sleep(5000);
					}
				}
			}
		} catch (Throwable e) {
			SDFSLogger.getLog().warn("error while checking file [" + ddb + "]",
					e);
			throw new IOException(e);
		} finally {
			SDFSLogger.getLog().info("done checking file [" + ddb + "] imported " +blks.size() + " k=" +k);
			ddb.close();
			ddb = null;
		}
	}

	@Override
	public void run() {
		try {
			this.checkDedupFile(ddb, fevt);
			fevt.endEvent("imported [" + guid + "]");
		} catch (Exception e) {
			SDFSLogger.getLog().error("unable to process " + guid, e);
			fevt.endEvent("unable to process file " + guid, SDFSEvent.ERROR);
		}

	}

}
