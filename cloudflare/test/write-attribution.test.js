import test from 'node:test';
import assert from 'node:assert/strict';
import {writeAttribution} from '../write-attribution.js';

test('write aliases contain only fixed code families and roots, never user data',()=>{
  assert.equal(writeAttribution('document','users/PRIVATE-UID'),'quota_writer_document_users');
  assert.equal(writeAttribution('chat_root','chats/SECRET-ROOM'),'quota_writer_chat_root_chats');
  for(const writer of ['DROP TABLE x','alice',null,undefined,{},'document'])
    for(const path of ['private/SECRET',"users'); DROP TABLE users;--",'users/private',null,{}])
      assert.match(writeAttribution(writer,path),/^quota_writer_(?:document|other)_(?:users|other)$/);
});
