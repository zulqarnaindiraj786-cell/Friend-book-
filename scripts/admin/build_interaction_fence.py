"""Generate a REVIEW-ONLY Firestore interaction fence. Never deploys rules/control.
The baseline firebase/firestore.rules and firebase.json are never changed.
"""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIELDS = ('likes','likeCount','likesCount','reactions','reactionsMap','reactionCounts',
          'likedBy','comments','commentCount','commentsCount','commentsMap','commentsList',
          'lastComment','lastCommentAt','commentsStorageVersion','pinnedCommentId')


def generate(base):
    helper = '''
    // REVIEW-ONLY migration control; absent document keeps existing Firebase mode.
    // Any non-firebase phase fails closed for interaction writes, not chat/calls.
    function firebaseInteractionsOpen() {
      return !exists(/databases/$(database)/documents/friendbookMigrationControl/interactions)
        || get(/databases/$(database)/documents/friendbookMigrationControl/interactions).data.get('phase', 'unknown') == 'firebase';
    }
    function interactionFields() {
      return FIELDS;
    }
    function noInteractionChanges() {
      return !request.resource.data.diff(resource.data).affectedKeys().hasAny(interactionFields());
    }
    function emptyInteractionValue(value) {
      return value == null || value == 0 || value == '' || value == [] || value == {};
    }
    function emptyInteractionInitializers() {
      return INITIALIZERS;
    }
    match /friendbookMigrationControl/{document} {
      // Server-admin control only. Client admins cannot unfence old writers.
      allow read, write: if false;
    }
'''.replace('FIELDS', str(list(FIELDS))).replace('INITIALIZERS', '\n        && '.join("emptyInteractionValue(request.resource.data.get('%s', null))" % name for name in FIELDS))
    anchor = '    // ---- posts (incl.'
    if base.count(anchor) != 1: raise ValueError('Unexpected baseline rules; manual review required')
    output = '// REVIEW ONLY: not referenced by firebase.json and NOT deployed.\n' + base.replace(anchor, helper+'\n'+anchor, 1)
    # Restrict changes to the three existing interaction blocks; preserve everything else.
    for start, end in [('    match /posts/{postId}', '    // ---- comments ----'),
                       ('    match /comments/{cid}', '    // ---- stories'),
                       ('    match /reels/{rid}', '    // ---- groups')]:
        begin=output.index('\n'+start)+1; finish=output.index(end,begin)
        segment=output[begin:finish]
        if start == '    match /comments/{cid}':
            # Includes the complete original authorization expression in parentheses.
            segment=segment.replace('allow create: if signedIn();','allow create: if signedIn() && firebaseInteractionsOpen();')
            segment=segment.replace('allow update: if signedIn();','allow update: if signedIn() && firebaseInteractionsOpen();')
            segment=segment.replace('allow delete: if (signedIn()', 'allow delete: if firebaseInteractionsOpen() && ((signedIn()',1)
            segment=segment.replace('|| isAdmin();','|| isAdmin());',1)
        else:
            segment=segment.replace('allow create: if signedIn();', 'allow create: if signedIn() && (firebaseInteractionsOpen() || emptyInteractionInitializers());')
            segment=segment.replace('allow update: if signedIn();', 'allow update: if signedIn() && (firebaseInteractionsOpen() || noInteractionChanges());')
            segment=segment.replace('allow write: if signedIn();','allow write: if signedIn() && firebaseInteractionsOpen();')
        output=output[:begin]+segment+output[finish:]
    return output


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true'); args=parser.parse_args()
    target=ROOT/'firebase/firestore.interaction-fence.review.rules'
    output=generate((ROOT/'firebase/firestore.rules').read_text())
    if args.check:
        if target.read_text()!=output: raise SystemExit('Fence draft drifted; regenerate and review')
    else: target.write_text(output)

if __name__=='__main__': main()
