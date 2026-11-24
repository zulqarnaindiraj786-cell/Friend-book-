import test from 'node:test';
import assert from 'node:assert/strict';
import {contentWriteAllowed,supportedCollectionPath} from '../document-policy.js';

test('generic badge fanout cannot mutate other authors or group posts',()=>{
  for(const coll of ['posts','reels','stories']) {
    const before={authorId:'alice',authorTickLevel:'none'};
    const after={...before,authorTickLevel:'blue',authorVerified:true};
    assert.equal(contentWriteAllowed(`${coll}/one`,'admin',before,after),false);
    assert.equal(contentWriteAllowed(`${coll}/one`,'alice',before,after),true);
    if(coll!=='stories')assert.equal(contentWriteAllowed(`${coll}/one`,'alice',{...before,groupId:'g'},{...after,groupId:'g'}),false);
  }
  assert.equal(supportedCollectionPath('comments'),false);
});
