grammar LNCF;

prog
    : d+=definition (separator d+=definition?)* EOF
    ;

separator
    : NL+ | NL* COMMA NL*
    ;

literal
    : STRING            #String
    | INT               #Int
    | (TRUE | FALSE)    #Boolean
    ;

identifier
    : ns=WORD COLON qualifier=WORD      #Namespaced
    | COLON qualifier=WORD              #Implicit
    | qualifier=WORD                    #Local
    ;

literal_like
    : literal           #ActualLiteral
    | passed_argument   #PassedArgument
    | WORD              #Global
    | call              #CallLiteral
    | '(' clause ')'    #ComplexLiteral
    ;

passed_argument
    : POS_ARG   #PositionalPassedArgument
    | NAME_ARG  #NamedPassedArgument
    ;

enum_key
    : WORD
    | INT
    ;

definition
    : DEFINE_GLOBAL global_definition           #GlobalDefinition
    | DEFINE_FLOW functional_definition         #FlowDefinition
    | DEFINE_FN functional_definition           #FnDefinition
    | MANUAL_ENUM enum_definition               #ManualEnumDefinition
    | FUNCTIONAL_ENUM enum_definition           #FunctionalEnumDefinition
    | FUNCTIONAL_SPACE_FOR funcspace_definition #FuncspaceDefinition
    | CLASSIFY classification_definition        #ClassificationDefinition
    ;

global_definition
    : WORD NL* EQ NL* literal
    ;

functional_definition
    : name=WORD (LP args+=WORD (separator args+=WORD?)* RP)? NL* BEGIN NL* body NL* END
    ;

body
    : (d+=call (separator d+=call?)*)?
    ;

enum_definition
    : name=WORD (NL* BEGIN NL* d+=enum_key (separator d+=enum_key?)* NL* END)?
    ;

funcspace_definition
    : name=WORD (NL* BEGIN NL* funcspace_operations NL* funcspace_members? NL* END)
    ;

funcspace_operations
    : d+=funcspace_operation (separator d+=funcspace_operation?)*
    ;

funcspace_operation
    : 'dimensions' NL* BEGIN NL* d+=WORD (separator d+=WORD?)* NL* END              #FuncspaceDimensionsOperation
    | 'order' NL* (PERMUTE |
        BEGIN NL* d+=(WORD | PERMUTE) (separator d+=(WORD | PERMUTE)?)* NL* END)    #FuncspaceOrderOperation
    | 'then' NL* BEGIN call END                                                     #FuncspaceThenOperation
    ;

funcspace_members
    : d+=funcspace_member (separator d+=funcspace_member?)*
    ;

funcspace_member
    : k=enum_key ARROW NL* BEGIN NL* d+=STRING (separator d+=STRING?)* NL* END
    ;

classification_definition
    : name=WORD WHERE k=WORD EQ v=WORD (NL* BEGIN NL* classification_body NL* END)
    ;

classification_body
    : d+=classification (separator d+=classification?)*
    ;

classification
    : k=enum_key ARROW v=classifier
    ;

classifier
    : WORD literal_like         #LiteralClassifier
    | WORD classifier           #SingularCompoundClassifier
    | WORD? compound_classifier #CompoundClassifier
    ;

compound_classifier
    : NL* BEGIN NL* d+=classifier (separator d+=classifier?)* NL* END
    ;

call
    : FLOW functional_call  #FlowCall
    | FN functional_call    #FnCall
    | macro_call            #MacroCall
    | if_else_call          #IfElseCall
    | SET WORD EQ? call     #SetCall
    | RETURN literal_like   #ReturnCall
    ;

functional_call
    : identifier (NL* BEGIN NL* d+=argument (separator d+=argument?)* NL* END)?
    ;

argument
    : literal_like          #Positional
    | WORD EQ literal_like  #Named
    ;

macro_call
    : MACRO identifier (NL* macro_body NL*)?
    ;

macro_body
    : BEGIN NL* d+=macro_member (separator d+=macro_member?)* NL* END
    ;

macro_member
    : argument      #ArgumentMacroMember
    | call          #CallMacroMember
    | macro_body    #RecursiveMacroMember
    ;

if_else_call
    : IF if_clauses+=clause (NL* BEGIN NL* if_bodies+=body NL* END)
        (NL* ELIF if_clauses+=clause (NL* BEGIN NL* if_bodies+=body NL* END))*
        (NL* ELSE (NL* BEGIN NL* else_body=body NL* END))?
    ;

clause
    : literal_like                          #LiteralClause
    | clause CONCAT clause                  #OperativeClause
    | clause (EQ | GT | LT) clause          #ComparisonClause
    | clause OR clause                      #BooleanClause
    | LP clause RP                          #ParentheticalClause
    | fn=identifier LP d+=literal_like
        (separator d+=literal_like?)* RP    #FunctionCallClause
    ;

POS_ARG     : '$'[0-9]+ ;
NAME_ARG    : '$'[a-zA-Z_][a-zA-Z_0-9]* ;
COLON       : ':' ;
COMMA       : ',' ;
BEGIN       : '{' ;
END         : '}' ;
LP          : '(' ;
RP          : ')' ;
CONCAT      : '..' ;
EQ          : '=' ;
GT          : '>' ;
LT          : '<' ;
OR          : 'or' ;
ARROW       : '->' ;
STRING      : '"' ( EscapeSequence | ~('\\'|'"') )* '"' ;
TRUE        : 'true' ;
FALSE       : 'false' ;
IF          : 'if' ;
ELIF        : 'elif' ;
ELSE        : 'else' ;
FLOW        : 'flow' ;
FN          : 'fn' ;
MACRO       : 'macro' ;
SET         : 'set' ;
RETURN      : 'return' ;
CLASSIFY    : 'classify' ;
WHERE       : 'where' ;
PERMUTE     : 'permute' ;
MANUAL_ENUM : 'manual enum' ;
DEFINE_GLOBAL   : 'define global' ;
DEFINE_FLOW     : 'define flow' ;
DEFINE_FN       : 'define fn' ;
FUNCTIONAL_ENUM : 'dimension' ;
FUNCTIONAL_SPACE_FOR    : 'functional space for' ;

INT         : '-'?[0-9]+ ;
WORD        : [a-zA-Z_][a-zA-Z_0-9]* ;
NL          : [\n][ \t\n\u000C\r]* ;
WS          : [ \t\u000C\r]+ -> channel(HIDDEN);
COMMENT     : '#' ~[\r\n]*  -> channel(HIDDEN);

fragment EscapeSequence: '\\' ["\\] ;
