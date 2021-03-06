/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.server.distributed.task;

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.version.ORecordVersion;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.distributed.ODistributedRequest;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog.DIRECTION;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Distributed updated record task used for synchronization.
 *
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 */
public class OUpdateRecordTask extends OAbstractRecordReplicatedTask {
  private static final long serialVersionUID = 1L;

  protected byte[]          previousContent;
  protected ORecordVersion  previousVersion;

  protected byte[]          content;

  public OUpdateRecordTask() {
  }

  public OUpdateRecordTask(final ORecordId iRid, final byte[] iPreviousContent, final ORecordVersion iPreviousVersion,
      final byte[] iContent, final ORecordVersion iVersion) {
    super(iRid, iVersion);
    previousContent = iPreviousContent;
    previousVersion = iPreviousVersion;

    content = iContent;
  }

  @Override
  public Object execute(final OServer iServer, ODistributedServerManager iManager, final ODatabaseDocumentTx database)
      throws Exception {
    ODistributedServerLog.debug(this, iManager.getLocalNodeName(), getNodeSource(), DIRECTION.IN, "updating record %s/%s v.%s",
        database.getName(), rid.toString(), version.toString());

    ORecord loadedRecord = rid.getRecord();
    if (loadedRecord == null)
      throw new ORecordNotFoundException("Record " + rid + " was not found on update");

    if (loadedRecord instanceof ODocument) {
      // APPLY CHANGES FIELD BY FIELD TO MARK DIRTY FIELDS FOR INDEXES/HOOKS
      final ODocument newDocument = new ODocument().fromStream(content);
      ((ODocument) loadedRecord).merge(newDocument, false, false).getRecordVersion().copyFrom(version);
    } else
      ORecordInternal.fill(loadedRecord, rid, version, content, true);

    loadedRecord = database.save(loadedRecord);

    ODistributedServerLog.debug(this, iManager.getLocalNodeName(), getNodeSource(), DIRECTION.IN, "+-> updated record %s/%s v.%s",
        database.getName(), rid.toString(), loadedRecord.getRecordVersion().toString());

    return loadedRecord.getRecordVersion();
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.WRITE;
  }

  @Override
  public OUpdateRecordTask getFixTask(final ODistributedRequest iRequest, final Object iBadResponse, final Object iGoodResponse) {
    final ORecordVersion versionCopy = version.copy();
    versionCopy.setRollbackMode();

    return new OUpdateRecordTask(rid, null, null, ((OUpdateRecordTask) iRequest.getTask()).content, versionCopy);
  }

  @Override
  public OAbstractRemoteTask getUndoTask(final ODistributedRequest iRequest, final Object iBadResponse) {
    if (iBadResponse instanceof Throwable)
      return null;

    return new OUpdateRecordTask(rid, null, null, previousContent, previousVersion);
  }

  @Override
  public void writeExternal(final ObjectOutput out) throws IOException {
    super.writeExternal(out);
    out.writeInt(content.length);
    out.write(content);
  }

  @Override
  public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException {
    super.readExternal(in);
    final int contentSize = in.readInt();
    content = new byte[contentSize];
    in.readFully(content);
  }

  public byte[] getPreviousContent() {
    return previousContent;
  }

  public ORecordVersion getPreviousVersion() {
    return previousVersion;
  }

  @Override
  public String getName() {
    return "record_update";
  }

  @Override
  public String toString() {
    if (version.isTemporary())
      return getName() + "(" + rid + " v." + (version.getCounter() - Integer.MIN_VALUE) + " realV." + version + ")";
    else
      return super.toString();
  }

  public byte[] getContent() {
    return content;
  }
}
