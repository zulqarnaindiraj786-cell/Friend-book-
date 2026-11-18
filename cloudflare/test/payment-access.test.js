import test from 'node:test';
import assert from 'node:assert/strict';
import {isPaymentAdmin,canReadPayment,canCreateFreeReview} from '../payment-access.js';

test('payment review ownership and admin authority are server controlled',()=>{
  const payment={userId:'alice',type:'tick_blue',amount:0,currency:'FREE',status:'pending'};
  assert.equal(canCreateFreeReview('alice',payment),true);
  assert.equal(canCreateFreeReview('bob',payment),false);
  assert.equal(canCreateFreeReview('alice',{...payment,status:'approved'}),false);
  assert.equal(canCreateFreeReview('alice',{...payment,amount:100}),false);
  assert.equal(canCreateFreeReview('alice',{...payment,type:'subscription'}),false);
  assert.equal(canCreateFreeReview('alice',{...payment,approvedBy:'alice'}),false);
  assert.equal(canReadPayment({},'alice',payment),true);
  assert.equal(canReadPayment({},'bob',payment),false);
  assert.equal(isPaymentAdmin({PAYMENT_ADMIN_UIDS:'admin-uid'},'admin-uid'),true);
  assert.equal(isPaymentAdmin({PAYMENT_ADMIN_UIDS:'admin-uid'},'admin'),false);
  assert.equal(isPaymentAdmin({},'admin-uid'),false);
  assert.equal(canReadPayment({PAYMENT_ADMIN_UIDS:'admin-uid'},'admin-uid',payment),true);
});
