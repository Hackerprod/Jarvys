package com.jarvys.agent.chatdb

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.security.MessageDigest

/** Current-data additive upgrade only. Never accepts pre-current storage or synthesizes provenance. */
internal object RoomTreeProvenanceSchema {
    const val UPGRADE_ENABLED = false
    const val V1_IDENTITY = "1ad574a6ba44dd5117e182c835f4f670"
    const val CREATE_SQL = "CREATE TABLE IF NOT EXISTS `crew_message_input_provenance` (`conversation_id` TEXT NOT NULL, `mission_id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `source_session_id` BLOB NOT NULL, `workspace_id` BLOB NOT NULL, `assignment_id` BLOB NOT NULL, `bot_id` BLOB NOT NULL, `source_message_id` BLOB NOT NULL, `kind` TEXT NOT NULL, `principal_role` TEXT NOT NULL, `correlation_message_id` BLOB NOT NULL, `reference_id` BLOB NOT NULL, `content_sha256` BLOB NOT NULL, `assignment_revision` INTEGER NOT NULL, `request_id` BLOB NOT NULL, `input_mission_id` BLOB NOT NULL, `cycle_id` BLOB NOT NULL, PRIMARY KEY(`conversation_id`, `mission_id`, `message_id`), FOREIGN KEY(`conversation_id`, `mission_id`, `message_id`) REFERENCES `crew_messages`(`conversation_id`, `mission_id`, `message_id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    // SHA256 of token-normalized table/index DDL from immutable verified-v98 schema1.json.
    // Source blob SHA256 ab91fb220c7b78fb9e69e070d46b635bdada53f8ba3d1a8bfec6b8bad63465f3.
    private val v1Objects = mapOf(
        "table:room_master_table" to "0bc68b05f8e7f5376f6c78211cafeee70af457d0fa5d3b60b1507bdd61d14b94",
        "table:conversations" to "9a02b1499c008f5624b7745a247e7aa7258e26ead22d2663cb3f726c434bfe32",
        "table:tombstones" to "198138a38121081958762df1697c94684f76fb5f07089af8aff4144dfdc1f7fd",
        "table:conversation_items" to "fdf5af426333e6b04da6426bf2a5701c718262fea8ff7bb1956afbd7b5106349",
        "index:idx_conversation_items_sort" to "6e37ae4e14c180ad3ce35a5cbe2c9f9b982152aa6b7d8dc21a0a012abb524b53",
        "index:idx_conversation_items_turn_anchor" to "0f734b694ba22ec5afa8f446696f2140f6f59629946a2175d4e23fc7a83881c3",
        "table:messages" to "e764583b2595a235aacce1a4aaca0e7a74270d77e05150e0fcc342b7e0bb2bc3",
        "index:idx_messages_conversation_seq" to "167f41eb4edcc5f1e794a6019266de0bc355973c911772bfc102f5ec7da5a34d",
        "index:idx_messages_conversation_role_seq" to "1b01b720fb6a757f469016c1d37cc64e810717e4dbd8b61e8ea31650d578c1e3",
        "index:idx_messages_owner" to "1a527e9849ba04f238d521b36030ba9c65c0ebe3c5c66021452a61e64485cf2d",
        "index:idx_messages_message_index" to "e40f3ba8e1ce672248aaad4a9eb7cc041f4eb49e41b5f178f9e910e29c42eba5",
        "index:idx_messages_message_id" to "e3b82f7a395f2378ac986dc387ed6b46f3c6beb3ea9c83a527c4bc9ac9db2d7b",
        "index:idx_messages_stable_id" to "34fcb04d786231f266f82e105ed23b58729a28f1933c17b277df7e3b46a228c5",
        "index:idx_messages_proactive_decision" to "529e6ea829be1178ac22f9f1d5458878b0e19303be8f8108f935a3bea9cae7ea",
        "index:idx_messages_proactive_thread" to "220ef088f565f26c32647e9d27ac0b3c6e455ed7da8e0de9a48160d6715c6263",
        "index:idx_messages_interrupt" to "ed9fc3589a3a40dcfb09dc87a14c5c948b4a909dc5e983a27748ac7679ca55eb",
        "table:message_parts" to "f6238a2e9b3117ecf49e79dd2f44c6ac1607af2096dc89b313febc3144387b2c",
        "table:message_attachments" to "2519bf5bb276b87d6d93048105186804af5d29db2e6429f423d760b37f3f5b5b",
        "index:idx_message_attachments_id" to "9251eb190ea8a90979e056c68e660590107bf546c93e370deb951a8257398377",
        "index:idx_message_attachments_file_ref" to "302b395c90153997589d9cbb9847406589cc5970cf279ef73de08a02d1ce292e",
        "table:message_translations" to "ebda43e4b924afc13f460de97e6cca66c15ec7c85acac88dcc1b10331077c2ed",
        "index:idx_message_translations_seq" to "2d58173d54fc120407dc2eea83b37eca0594dcf3780e76d79a306a1b6cbdb34c",
        "table:message_reactions" to "46e6656312ac6f138966ef81dc0daaaccb72c03ea0abadcdfa8103423a151840",
        "index:idx_message_reactions_seq" to "1ebb548d5ab58b790fe6ea4869e760f175c1a5d9cc6c2ce830a50d5e5f3699b6",
        "table:proactive_suggested_replies" to "0ce239ec5160ac4d1c6e5c3831d84c62ca895060c7939866ba98a03f195bf4eb",
        "table:user_decisions" to "0e1110f798a58913042f3ed51267fd3163f0f6950d7da0488d56f64602a6375c",
        "index:idx_user_decisions_seq" to "738862ddc309cb05c368fc605609db0aad49be92bf15bbba9c4186fd9b27c47c",
        "table:user_decision_options" to "062451cf29b13299a9f823fc0cc729ceffd121623304b57ee0369a29f5e7c4fc",
        "index:idx_user_decision_options_id" to "adec55ad4bd090002a82c796737a7d6d11b9f56799419b126e3010287817f2dc",
        "table:interrupt_requests" to "8cd9be2733f5cb2890bd913ac7830b10ed4743918b752f157a8872242baa2d58",
        "index:idx_interrupt_requests_pending" to "9f4aa79c2d6d41e1558b8ce9141063bb0e744f15fbd2e0f94e57bf24af62df37",
        "index:idx_interrupt_requests_dispatched_seq" to "fe1a566bd4b7549e493b6426b6ab3121c5d4a391fd5832982f351d2fd6ba4147",
        "index:idx_interrupt_requests_input_state" to "4c954b00f8eabf6870d230482b87364d29826bd629094b5b6de5c88ef9853616",
        "index:idx_interrupt_requests_seq" to "d3a5056d90812d8d35859b32bc4e72ba85149a05fbf976d9322971600022a43c",
        "table:interrupt_request_skills" to "e102795163ff4cdd29e69483745b1bb32ca117d42e27e595f6ec2400ddba6f24",
        "table:tool_batches" to "f5f58347d232d4f55cccac10d87455e19e0fa33642b8272c6abc870b2396c161",
        "index:idx_tool_batches_owner" to "a163417d8642ac4846c6699eb491c9de51a9ca0d0a8e2f765b61e1f7d3155601",
        "index:idx_tool_batches_seq" to "a6c650516fd9377e4ad8a83237c2c6f6bf1f6d5499d2a056239da52d88db39f6",
        "table:tool_calls" to "c884973fb67b38b9f452ea2eb7e89782f3fc8eb24c84dea5b3ffc18cbd270887",
        "index:idx_tool_calls_ordinal" to "d74b21ff1eb9fe95c398d36fd1dfb3d7c889ef5bc068decaf944330cd5dc5725",
        "index:idx_tool_calls_call" to "b94086e0501251168fa1cd9fd5a569540bd7876ecc2c03afb19e2102cfe5a1e5",
        "table:tool_results" to "8967e190d2548af65d2838da199c4f2bfbcc2db1ea64ee28df645f6bce076296",
        "index:idx_tool_results_seq" to "34ccc6524877c627f270dee692fd4b04a6e3504cd3dd9d5dc380b0df484e48a2",
        "index:idx_tool_results_artifact" to "dd5629425f961573ea5cfcbe86c095558a8383a574fd73e9107c4f1d39195a8a",
        "table:tool_activities" to "e117c405a9840b65f9c6ad415cae933fb0ad39d796f7996eb3f056a89a1812d7",
        "index:idx_tool_activities_event" to "0c4eef0a319926a755c4d8c110387dd7ad7c7c3bf9917ab342e869571e9678bf",
        "index:idx_tool_activities_owner_execution" to "2324171453f234edd3cc0dbdc93e1f62a55f1f720fbe0003afa438860afad1c7",
        "index:idx_tool_activities_batch_call" to "22dfc560e25693f0bde454ff2f9b5b0d8047ab3c3d8218da13fe7e35b2c9eebf",
        "index:idx_tool_activities_execution_call" to "f9a9859b1f8d09667b48be74a9971de505c2861e1daa7d5ce4efc22928ca01ed",
        "table:tool_presentations" to "3b894a13a00e0cb141901d26238fc05ee6466fb170919493a5db17806339273f",
        "index:idx_tool_presentations_batch_call" to "ff57bbc4f16406509621936953a0de08341792c86525d451cddc2a006d661ae3",
        "index:idx_tool_presentations_owner" to "da0d72b33b9cb902aecb1e55484e15d7bb6bb581c0a4ee0c976edab8e38d8007",
        "table:reflection_tool_markers" to "2e5a92df40acc2def5779acb5ece458b57ac97b0902417da5e361bdaa32c4bf3",
        "index:idx_reflection_tool_owner" to "8c69b6ca8674171e59a5a09d532da62aff6077fa0ed2be29f5303411ff002b06",
        "index:idx_reflection_tool_batch_call" to "e3cd6d0739218827a4775c4912cc9668a31ecd7d79b1eca25ebb515216fe67f5",
        "table:model_errors" to "c09e1eb277922fbdfe9058794465f40bd752758c552dfddfd82dcb8e20822b98",
        "index:idx_model_errors_owner" to "56ed91d1edff7d06443607540096eb1a7acefe2e2e55c904ee4090886756acae",
        "table:model_provider_outputs" to "a9735aea58b6dc772bd51bd435628fe6e1758dcc1eba9feba1111c9855c39a01",
        "index:idx_model_provider_outputs_owner" to "1e624b494c828b7f70841a00d65cd0b3a084c478c96760a3ebccca403bf11fb9",
        "index:idx_model_provider_outputs_message_index" to "25c42be16b8ce54c57f2561a1a2a5f53f6f2817923d2b6103bafdf5f7b4af91c",
        "table:model_artifacts" to "80934909aefa1af716cda5f7ea8b51da6a4a3028e1a1cd63bc0ceb2c1ff5dade",
        "index:idx_model_artifacts_seq" to "dfaaca90d683bba227fac88783f5c58331052a55c7a59d313c5110a599a2cd62",
        "index:idx_model_artifacts_file" to "cbd6f22e992c786274c13456d0085f2ff1bbea04d32e5e47452bf4a1a50a365a",
        "table:artifacts" to "35a6945a58829c2c6688287553dfcb6d9c64f6be192503a205b0761978835d7f",
        "index:idx_artifacts_seq" to "09c31f2c794d9be537a418ff52a707dd54667af0f07f3bd9bd19ef278f1af4c0",
        "index:idx_artifacts_file" to "a541a53b8daeec12d3a29260086cfe3603d811baeedfe5a743826f216ba25b2e",
        "index:idx_artifacts_thumbnail" to "9bf48ee6132c1a92a0b52b3081730daec5316a33478d2f850f2694218479490e",
        "index:idx_artifacts_type" to "8552aeddf440fb74049f922a59412a13a023ffa1288a5be6f436e310fc8c8d85",
        "index:idx_artifacts_module" to "e7891e4f018d41c6801f09135f897da9bf98a1fd7661259d5da7a6578a3e95fb",
        "table:generated_images" to "fa1e5d1734c0bcfdd57ce49471a3cfbc9b3fbcf44dfad27ef989044486539660",
        "index:idx_generated_images_path" to "cc61162b242db2f8f656a6a9d60c5f81a670e250d97b40eb83fda4b3d9f54cae",
        "index:idx_generated_images_file" to "70bff7718524751e6e584209329810a35229cd644e569feadeadb1b15f009831",
        "table:compactions" to "1c5c491f2bedd31fa5b56a0e06134af6407bece7673e156ad95a90929f194434",
        "index:idx_compactions_seq" to "9da8f5a69a8a2b7dd605e79e0072c93d0b61bf1dcd18325dfff7d075c6ea4cf5",
        "table:compaction_reflection_acks" to "db18464c28ff5c71621cda59749cf8800781c0492418a7d4214382b1faca2b39",
        "index:idx_compaction_acks_seq" to "80cc378c8033dca7ae0ed43cdb62e0bc4b5f8bc95d5ca316cfead25e653d4e59",
        "table:reflections" to "5376fd2b97d357262d85fab67c3453f1b789b5d1b0d4e582552123287ecc206c",
        "index:idx_reflections_id" to "b4a6030653c78fde34b4d80411abd7cceef57f81bf757299a1e60e2f33634f51",
        "table:reflection_revisions" to "eeedc7dddede22d4644502079bc30dc0fdbdf656b67129a77446093ed86f4faf",
        "index:idx_reflection_revisions_id" to "c748d124d5858f7ff364f4e17629d623b974e04b9d592d5f1112ead6776d426a",
        "table:crew_missions" to "06075490fef2d256d69be2bc8c964193c4d993df5d242222aee9cf83bc07c6f3",
        "index:idx_crew_missions_first_snapshot_seq" to "694be00968b7a5c6f349eaefff47c15d25374f450f1a1fd5fec52950ed024b05",
        "index:idx_crew_missions_snapshot_seq" to "4c37d6aed8b7dc8c2e0bcacd2356c0771bd1eeaef430cfb032c1f93939b535cb",
        "table:crew_bots" to "d33690d11c32dd609eda44710e524d2ac31e10550e15de287f5d7ea8c4f3288c",
        "index:idx_crew_bots_ordinal" to "8917277ae140e8c061de907b0bfc18b44f29853c23d184db4a7c126557f80e19",
        "index:idx_crew_bots_evidence" to "610d4b92e99efc037f1797afed78cc2c11ba2cad672e1c7e8c95890187c08979",
        "table:crew_bot_tools" to "1d80acdf0eab2e8b544ee482e962c989f022ed876ea9fd5094f41701a1bc1cad",
        "table:crew_messages" to "3d694fe7bed7524b81827f3ec38eea0820dfed2876fdb66f941efb4b9efaec6c",
        "index:idx_crew_messages_id" to "d1f10e6ddf88bc64a2485eb0912d74a560c2bc9a5cf22b2e06fb003b7ddf32c8",
        "index:idx_crew_messages_coordination" to "787e117dfebccafb3899468628fb7dc42a32f543a2ebec08d98395f2208438a5",
        "table:crew_message_activities" to "2ef2a078c2e1359ababa862ca03ac9eae2da97eefc330590a31be45a7bfd858c",
        "index:idx_crew_message_activities_event" to "e72d4702114f69196bc626f8a267ca17b655e33d76b75dcb838679abb41be84f",
        "table:crew_message_input_bindings" to "3dbebb6499aa9fd3a4da629c43a96a5877f5086980c4fd96e4bbc6f2d0ac416b",
        "table:crew_message_refs" to "7fe7d205fffaafcdaab436acfe91bab82bbe3f83695e52f182bcdb6b0db618be",
        "table:crew_result_evidence" to "0c3c2867f0bb7f0ef41ef176c7d2c71e509fac0d252730f09104f2c657b03569",
        "index:idx_crew_result_evidence_mission" to "cd1371acd5886bf6477a024901be3c5e15feba2538cc77ad8daaf6b60caa86d9",
        "index:idx_crew_result_evidence_seq" to "dcbafc6d4c784e7eac3ec7e525c6a6db2463b0830be1709107bf7f9f401b9ab7",
        "table:workspace_result_refs" to "cce2fb1d68fb23ace783729b928c5738bc211f37ee57477b99c4f3e715730341",
        "index:idx_workspace_result_refs_evidence" to "1843b3d9f62979715012fc5b6742a9e911ab1d8e6c7c1f23f6f3595f0feb3af5",
        "index:idx_workspace_result_refs_seq" to "2387afc3042b6372a86542c65492b5798d832e5141153375092e02c8f8d77005",
        "index:idx_workspace_result_refs_notice" to "518b4aee2b9b1c0215efe56b191bca55dc108c03b6d0c46ed0fdeb9a680dfe42",
        "table:crew_coordination_evidence" to "b0cffe273004265d3aac1dbea1890b3745a165a8bf75816f7f7487c5a928e383",
        "index:idx_crew_coordination_mission" to "1c0f5c9aca65cc66ed20565536fff9a85a03d0a02093216378dcb92ddfb1dcdb",
        "index:idx_crew_coordination_message" to "af529d3ced1a7afcb63e69010bff8a7971daae66c0c1d26a1baf6e88388cd704",
        "index:idx_crew_coordination_seq" to "7dc00a81764b9ce69abad4c90c49e823e7034d8a8d1c7041b21faa0a63742ff3",
        "table:crew_coordination_evidence_refs" to "69f8e405cc3c13eca0d3971767890ac399fe039e5ee66b0c24d25d742c095538",
        "table:workspace_coordination_refs" to "e8e4dafc3a475c9a48e3ef60e7fab0701ce42afb993998c519a04bfc1e9ac3b8",
        "index:idx_workspace_coord_refs_evidence" to "2c722b8253fd40d234becef234590422ceee2476be71caa77ceb5644d528f7a0",
        "index:idx_workspace_coord_refs_seq" to "99595406fa094ae0843173d8322de4854c3183c09e5710e81c9b91c3f6c5f36d",
        "index:idx_workspace_coord_refs_notice" to "7685cc02a1f34e8d017b4cbd79d647a4b1cedc6c1b21fbce751544c749143f6c",
        "index:idx_workspace_coord_refs_message" to "8079249d8f4f11fd585737bbf20ab5ee7493c8f93e9af1e1d3d30250f406d0ad",
        "table:workspace_coordination_replies" to "faf1d9808ddcd9f092b475f050fb6047741af03e6c9f37d508595bbf5dd24152",
        "index:idx_workspace_replies_reference" to "8981600186d24dd11874ab5d0cb24007a3a7e9b90c0febad699432e185fb8b8b",
        "index:idx_workspace_replies_conversation_reply" to "571440e38314934c5f94686c05527e3e867bdb4c32114d181f8dd4384fac63b0",
        "index:idx_workspace_replies_evidence" to "aa794731d613646b1e1fea32564861144cfa01e49dac047624fc111c1597ef0c",
        "index:idx_workspace_replies_seq" to "38f4f3fad09d11ac68bd2c9e10c43549372aa2124c1c5bbe254207f65d614de7",
        "index:idx_workspace_replies_state" to "08ebe04482d8a7fc92c4fd5816ae583ad66378c1c907cf575c58bf9ff95dd8c5",
        "table:workspace_coordination_reply_transitions" to "1f07c7dfd10fbb5a508f50c34143b3092709cf113a974830042d5cb33e80d385",
        "index:idx_workspace_reply_transitions_seq" to "045121242f6aba335b0ded8469e8307c6ac07447eef38a7ddb7d097295c32b11",
        "index:idx_workspace_reply_transitions_state" to "2bd407266648250872d7a47973f1760ca5ca02b967b2c60120ec29c733183552",
        "table:workspace_input_incorporations" to "25ea1ba7119bfc6526a902260a167092434c5c8fabe3b847955ce3446cc536e7",
        "index:idx_workspace_input_mailbox" to "926e9d93532732d34fb80cbdae4b1d19d9fc81c5d16686d15efcfbd7836d3562",
        "index:idx_workspace_input_source" to "5f5ed99550cdd2f87d7bafa41bcd88bdb151a4e70babbe4be9fbd05cb78fda40",
        "index:idx_workspace_input_seq" to "c3e3e94ab393035cf075c4fe9d215b76d7fc7d2889f619c5e97b73846d7edde4",
        "table:file_refs" to "a7b63eaa2160e9fc14220fb9bcdf377aed3f22d0668136446f4e64389a711abd",
        "index:idx_file_refs_relative_path" to "e69359ba19db6383e62bdf5f5287bc95dd60432388ed5dd0373cca9373ec9271",
        "index:idx_file_refs_sha256" to "c831c27fe230a05565a1d494a82037faebb8bc7670b677df52b0a3e48bf75217",
        "table:generation_run" to "afa7e4762a2b7a9710be236d476a1e350e8ef98b8a5560f723b323a9d1e42544",
        "index:idx_generation_run_active_target" to "e13bdf5a164965c5a992f1938aa15a3f2db2850091a3ef7412c272026d2c48ea",
        "index:idx_generation_run_message" to "9c5970abe1e7559773c84334f58e6ca282837beb65b92b1c536328ae6bfe96ec",
        "index:idx_generation_run_state_updated" to "66f141f41cd9a911872989ebcee3271b1751eb9134427fa9ba9e7c182b56de9f",
        "table:storage_meta" to "2b2353a75099f114c771e434f311625526ea14fc732b520442ff2645514007a2",
        "table:agent_profiles" to "4b88077b10b635f161932ba92a1bac6eac5e9d5e4ffc40bfddd1da9904484dc4",
        "table:agent_docs" to "8ddea3cea0695706eb0d92955b58229ea3df734167b9c28b28a05e66a7f0f10b",
        "table:user_directives" to "0799e00e1b0549fc47590478df78c37882640a2ffc4109b3961241331891f392",
        "index:idx_user_directives_active" to "4af7d1a39158b5e3de19cd1df397a1c87c8fa579cde57a8fc50aee5d17f60472",
        "index:idx_user_directives_source" to "5ec37d2834feda66e82c4ae4c303c4fd4c41cf67b16a383663b2b4e96ed66e69",
        "table:memory_entries" to "2f105131e8fccd31e50c67ccb72cbc60ab2cbe20e1312a83b4f94bf85e59a14b",
        "index:index_memory_entries_memory_session_id" to "70216396639c0316a5a9c5d4cfb36b137b22792d33eee5ec23a8b3ce963762fd",
        "index:idx_memory_entries_layer_day" to "adfdbe3b222203929df8433706325d3da176def36671a7d4ab5ceead468819d9",
        "index:idx_memory_entries_deleted" to "80855df2ba605c6cbef61953cafbd7f12e0904e6edf325b8a6a260db757b785d",
        "index:idx_memory_entries_kind_event" to "687ed0c0266977ec17e95b796cffdd8b0e916e31cd529e46cd8bb58c20c2587d",
        "index:idx_memory_entries_source_message" to "4b8558b6e6517a06e2dd287fb94e1d795b8a8d7ae0f24c82f9a97d386ae94189",
        "index:idx_memory_entries_topic_key" to "1851e1e80efacfae528cd7bd657a0d4f68ba064ec646c0f45a1390c323c00c43",
        "index:idx_memory_entries_content_dedupe" to "dc12f79109ea8ac32d7eb825bd273caeed4817746922fd97536e6caaff5f21ba",
        "table:memory_project_aliases" to "3cfd71a79cb584832b05d6f5d8552d558ec129aca1e2a85a8987dc6306d31d95",
        "table:memory_review_clear_events" to "d941c24e6e601a93587d4ae12a2e306cbe10e19978a9772a72a517cc9a1a4f81",
        "index:index_memory_review_clear_events_event_key" to "93e6cf8ebf512df8d93917dc39c3aa67a4f8d5921f207a810393540212e9a380",
        "table:memory_relations" to "20f7fc71dd9297e6d6231c9232685b2d5374a763316a955ea7529268da2127d8",
        "index:index_memory_relations_relation_id" to "1f7307bd43d878537a2556d01f36e3098a9ad27c1fdd90168e22c81f1f2d4c1e",
        "index:index_memory_relations_source_id" to "4f3309d3c944e9d4de104b6cb7c014141fb7eaee9ffac13fd91b3227a8457d2b",
        "index:index_memory_relations_target_id" to "ef4ea67a146442aa7ec42d524ca81198918fa040d4f1f72bbfc3c71cf59a1018",
        "table:memory_sessions" to "71f55af20bfa2e411c8adf4a4bc07a8bb8e5521ba283be98a90611b7c3959b20",
        "index:index_memory_sessions_project" to "7a3b04bbe0d26dc1311891a807fc2035e7308e1023c6bc87ea15be1f0e78c447",
        "index:index_memory_sessions_source_generation_owner_conversation_id_owner_message_id" to "0bef17154b06cd0ad4e85cbd91a629218d0c94378eb245f4523b0dd306e0f8f4",
        "index:index_memory_sessions_resume_event_key" to "044381d893bc2e4f6e3c1e2911b854b9c98cb619fa83c3b5b569f4dee7c39fc6",
        "table:memory_prompts" to "db4428be0acae9f45fb13ac663792affeee713a50ece25d22c8b8ea2e0dc90d0",
        "index:index_memory_prompts_session_id_source_inbox_id" to "e8822511a7c3d6e0b077ee036d5db066f0906876b13b0fe6f8d721c51c9ef256",
        "index:index_memory_prompts_project" to "2be4e283f44bf7f23cc1c33dd867da6bebc36513ad5409c952413bc63879f66e",
        "table:memory_prompt_tombstones" to "ee8fa198d58b48db133f0a953ddb9d11e1f87a337e831951d70f5e59bbb8a233",
        "index:index_memory_prompt_tombstones_project" to "aef682f3ded7649bb563bd9972f9add5bab9918754d173716846bfcb0c9cb958",
        "index:index_memory_prompt_tombstones_session_id_source_inbox_id" to "081007a5809796f29639fd39d91d496af56d38b5460d05825025be2e0ae70d17",
        "table:memory_session_tombstones" to "b4d5b2579d8976b1ffd9e549287fcf2a0a343f2185ee366a3d1b0804b20ea749",
        "index:index_memory_session_tombstones_project" to "ee55ffe84f08f9c07f1edf58342075b56b35cd6b4b0af24f39b7958750048b25",
        "table:memory_session_finalizations" to "c95e0328a469936eafb5693738aa25e75cefa2afffe6719afd659edbe6b2cdcd",
        "table:memory_deleted_observations" to "616bc2f4eb5ad7402dfec555f51d6b66f8e5bc6f6ab44631d838d7acc3fdba77",
        "index:idx_memory_deleted_producer" to "8d6455d9b0b93ba0b6ee0fb1071a87272332abbf1ed4fe83fad902658ec3e3a8",
        "table:memory_save_operations" to "1632cb4219a69dae7f59b842b887dfbebc3bea42471fe2a3ba1b303d32e1ab54",
        "index:idx_memory_save_operation_target" to "c5ba3da6cc76fdac5f87a271c3c4d2b52ef634387dfb2346645708dca438ac73",
        "index:idx_memory_save_operation_created" to "9ddc81b38a30ae7bde9a099989ff856ace4d405988934bd9495b5b6fce88b896",
        "table:memory_evidence" to "bc5725d1ca4ed07c1d64ed100398bf680b543469adcaa181629bbb93e5708759",
        "index:idx_memory_evidence_ref" to "3bc94922781dd3b8373a08160792330daa0ecde5e29f8888b5f39ba3182148d8",
        "index:idx_memory_evidence_tool" to "c02b5c4ba23de07f12025cd1e8dcada498a9f8155e17797ca119eaab2daf181f",
        "index:idx_memory_evidence_connector" to "bbd1578b952778ce68d04574bac3b3f245d33720b929f08ccbfb259850686712",
        "index:idx_memory_evidence_batch" to "04c0a6d950cc433c02a1d2eb88093c9c822ca924eacdc332c77d53c6f25d713a",
        "index:idx_memory_evidence_run" to "a366c59e6cffb02e8ce24853eb8e40c405997e822d9d0b15d0042220593a4412",
        "index:idx_memory_evidence_pending_process" to "8dbb72c254a31008189b78cf5ebf66400eca6c13af92af1d784feb7b21b937e7",
        "table:mission_episode_inputs" to "8d7b4fd82a5865b9b82b7ea5a9c8c87597be6c5c8b3a4dbc7a638d26497eafb6",
        "index:idx_mission_episode_inputs_source" to "1bcfd7cffebfaa5df94f39cbe5bdf27ceadca374ef0968d7780dafbcd67b078f",
        "table:compaction_coverage_sources" to "684525c433124c432a1cc068bb74442b35c4a2e7c61cd6456f1023388777733e",
        "table:compaction_flush" to "8e98933e973bb8cdca941d5eea46be0c932af8845ae2fcf27ee8f96d51421d33",
        "table:memory_links" to "1404aba86ea0d2f1779dc3b523cc1408aea5971838421057ded3b7f56550151d",
        "index:index_memory_links_to_id" to "97044a712c4eb8e0a664211cd04e9fb429a1d494252f34229c6ccd3955101dec",
        "table:transcript_artifact_refs" to "d9cafcf819c23f1ebd8207dffd9ae5b151a95c27a41a88fc1fb81ca5fb3c8afd",
        "index:index_transcript_artifact_refs_conversation_id" to "b76e690aca59179a32b9abcfa7c65f9dcdb78577ffdcd5731eb14347543c5d62",
        "table:memory_revisions" to "e59132f626aa03b3f89aa990c2e5607c11856be9b003ceb1cd0cb6caa3d5cadd",
        "index:idx_memory_revisions_target" to "9670213422a25a95805ec59cd3d1b7562b81b6586883af948ffb4cb964332798",
        "index:idx_memory_revisions_snapshot_version" to "2a5b9371242573a43c610db3dc5537b35bc3928511fbad57797af3e502c8f66d",
        "table:agent_session_state" to "0a2df7dec6b531c468f2f3507550c705ced2c0713ccf802d27b55c4cc93b71c2",
        "table:memory_jobs" to "e3cc653fdd18f5c4037e953dd7ec9b27721cf4e99fa78e3505be6729df2d7d3f",
        "index:idx_memory_jobs_ready" to "e83895f87bb1664c6a7330739577c5f9310bffab757b3002d7bef7f43dcec362",
        "table:connector_autonomy_audit" to "694e0f424a2945c864a4cd5ef1d9748143fa3ef0a02a972d205b057617f572c5",
        "index:index_connector_autonomy_audit_connector_id_timestamp_millis_id" to "76c3786bffa265b75258a1a54c7c40d9f02d89ee322b252f9522e133dee21190",
        "table:connector_state_events" to "9f46d2101dcd020a18a8bf3c96ac781460ee4c5838cd4be0bb2588cff3a63df9",
        "index:index_connector_state_events_connector_id_connected_at_ms" to "e4c535e2c8cd5691b15b7d1417a6acd5167af469066cc6f8eb70bb3a12a62868",
        "index:index_connector_state_events_store_epoch_sequence" to "c0c2b35a91ecf9150c143a9af706f82e3d5f81618136aaab3e56fb3534359e8b",
        "table:coding_job_bindings" to "c08b6eaf76780f7e1e98e6d5c2ab64f0ad74bd99a94d85bb40be41031773ba9c",
        "index:index_coding_job_bindings_submission_id" to "b7ada6f347aaeda3890bac9497d729db7019f7827ea265a35ea63592bdcbb3f6",
        "table:coding_job_observations" to "4b54bb2b8de936abe121c0706f24fe75aa6f82d0861e4ecc3c8af1762d9617ae",
        "index:index_coding_job_observations_job_id" to "e708df02316bfdb8dd76f736b266408dc8954efcfa5a5bb04ac3e6cbd1252828",
        "table:coding_job_semantic_links" to "6680524fec6d8cbad3f5c3d7db88853878c49fc059394b71e4704beb847873c6",
        "index:index_coding_job_semantic_links_target_entry_id" to "fe6b050cf909b2a4ce6ca1dc528285bbe7d8dc5117749eb73fb11ce79f438b53",
        "table:coding_cancel_requests" to "d2ffcfc7c4df77f0a41fc7428b43e74fc29d97450cc23098274fc3e728367d36",
        "index:index_coding_cancel_requests_source_key" to "ed4a6b86ad0351423d7ca8770df16c26825166069a73e5ef09104e6fd117140f",
        "index:index_coding_cancel_requests_target_job_id" to "812e74030d19e927a2b017424f946064c4694ec966579caf0e214b0cb3813300",
        "index:index_coding_cancel_requests_target_entry_id" to "a4f1349c6316683c65f7405baeea35bea14d8d6a602b4cc48c6fe1be6db779c6",
    )
    // Native-v98 auxiliary indexes are outside Room's entity schema. They remain optional,
    // retain their exact supported definitions and are never installed/rebuilt by this migration.
    private val auxiliaryIndexes by lazy { buildMap<String,String> {
        put("index:idx_memory_entries_topic_active",digest("CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_entries_topic_active ON memory_entries(project,scope,topic_key) WHERE topic_key IS NOT NULL AND deleted_at IS NULL"))
        put("index:idx_transcript_artifact_owner",digest("CREATE UNIQUE INDEX IF NOT EXISTS idx_transcript_artifact_owner ON transcript_artifact_refs(conversation_id,owner_kind,batch_id,IFNULL(call_id,''),artifact_id)"))
        for((table,id) in linkedMapOf("memory_entries" to "id","memory_prompts" to "id","memory_relations" to "relation_id","memory_deleted_observations" to "old_id","memory_prompt_tombstones" to "prompt_id"))
            put("index:idx_${table}_portable_identity",digest("CREATE UNIQUE INDEX IF NOT EXISTS idx_${table}_portable_identity ON $table(COALESCE(portable_sync_id,$id))"))
    }
    }
    private val virtualTables get() = mapOf(
        "memory_fts" to setOf(digest(PersonalMemoryFtsSql.FTS5_CREATE),digest(PersonalMemoryFtsSql.FTS4_CREATE)),
        "memory_prompts_fts" to setOf(digest(MemoryPromptSearchIndex.CREATE)),
        "message_fts" to setOf(digest("CREATE VIRTUAL TABLE message_fts USING fts5(text,role UNINDEXED,conversation_id UNINDEXED,content='message_search_content',content_rowid='rowid',tokenize='unicode61 remove_diacritics 2')")))
    // Literal trigger/view definitions extracted from the same immutable v98 callback sources.
    // No callback is executed to validate, and no existing auxiliary object is repaired here.
    private val auxiliaryDefinitions = mapOf(
        "trigger:trg_conversation_id_immutable" to setOf("b6ee413f42c8d07b8b995cc294b3929659bffcffe18c466fb605878db39a6643"),
        "trigger:trg_conversation_no_replace" to setOf("8e0a026b40d54f81e88ce7fa7be9ca0dfa25de34daef811985c1e10202d66569"),
        "trigger:trg_conversation_no_tombstone_resurrection" to setOf("4fd3f7fb3e86a8894c20baa676b0c59355a764901af59db3d79a6760e60c3fd7"),
        "trigger:trg_conversation_tombstone_after_delete" to setOf("25d1403454e62abcfc7594abf2a8a36128368e48ac0ba9a9332875de49ce70d6"),
        "trigger:trg_generation_run_target_insert" to setOf("1bb61ea26f6e69ca7733f14544cf7528634fadf5483dc8f04ccc0f3eaaaa3376"),
        "trigger:trg_generation_run_target_update" to setOf("4cb0c6c47a39ff669222a4cf1523624f2e59518f78c8bf79fe84aae404a4df62"),
        "trigger:trg_memory_deleted_observations_deleted_at_portable_precision" to setOf("625d9809012c81dd55f4897cbff2a8724469800c1b49eb1408e3a8ee0bed54ae"),
        "trigger:trg_memory_entries_created_at_portable_precision" to setOf("fd199aa5ce719da61f3365aad7d101f085d0a43c1d883b9a40ccf782c9b7adda"),
        "trigger:trg_memory_entries_deleted_at_portable_precision" to setOf("d2572c6935001f575c677b19f3324732b178f3e69480fc567fcd43a8b2daddc4"),
        "trigger:trg_memory_entries_last_seen_at_portable_precision" to setOf("284af17fdda639009c452732fbdc3952e44f2f06083b492612a4061c655ff9a5"),
        "trigger:trg_memory_entries_updated_at_portable_precision" to setOf("84bcfc12dcac7b7b41b62581a2a39d333dce7a985cf3bc674c3503cb314a22a0"),
        "trigger:trg_memory_fts_delete" to setOf("e08706ec5084a4689d2b9dffd6bd8e96df5349d2887793fa20e28c9cb9059875","9c5d929597f5fdf1256e94704e2954e887f4cf48219581d3fbbc5f926456b90c"),
        "trigger:trg_memory_fts_insert" to setOf("17477d806b0be1092a04a1db270b16729cf503c9f01ded6d6251e948a8514d2d","9df13a67a606ad0569fb2bfd633178ee6263ed781eb17e52e173fef3d16875bc"),
        "trigger:trg_memory_fts_update" to setOf("bcd631f2d073c166d6b4ba13db0dbb74d10109b72862db10c6e63dc284c0cf99","7a730edf79a66aec40d8c09a3286d12d6ef3e1e5525e698124e6d73de5d0b8f8"),
        "trigger:trg_memory_project_alias_kind_insert" to setOf("9c1cf5369d1362dccff07c0e2dde36cc6ed039236df26037b622b00f63578722"),
        "trigger:trg_memory_project_alias_kind_update" to setOf("c5feada7f45bf10cec46e2583986bc87ce9c57f313fb1a27e0e4c04c576ca839"),
        "trigger:trg_memory_prompt_tombstones_deleted_at_portable_precision" to setOf("b5a5760ae92b3ca0f688c33d1ac3ecf9c50763255cc957d6fde941fc7a78e195"),
        "trigger:trg_memory_prompts_created_at_portable_precision" to setOf("50064f7bd1268c2ac2779564cb8df4101847694ca9790345d9305677d5331d2a"),
        "trigger:trg_memory_prompts_fts_delete" to setOf("49fc8a0d1ebf0fc20583338ef559b8b9aa0215a706e1fcfe4d9f08fb8e94ca03"),
        "trigger:trg_memory_prompts_fts_insert" to setOf("5c5b02a27a4ed42ed85089f0d352a2a8fcbff3c666fd905e7ad125856eb70f83"),
        "trigger:trg_memory_prompts_fts_update" to setOf("e4709648b34661b3957374e12573c27cdba94422baf3c5943a2256463eb148d0"),
        "trigger:trg_memory_relations_created_at_portable_precision" to setOf("230f804bb5dda8ebb2094c3cf8464d148ac6b7b08549ccac86c47f7104b6221e"),
        "trigger:trg_memory_relations_superseded_at_portable_precision" to setOf("e0b036361818166b4e04fbb62c3754f4828771808b63c82ec9716760f255d4eb"),
        "trigger:trg_memory_relations_updated_at_portable_precision" to setOf("125708cfe5b23995bb0a9e8b71206da0682e7a290eec3beda8772749b85b1b7d"),
        "trigger:trg_memory_session_tombstones_deleted_at_portable_precision" to setOf("f5fd49d73be35326a7f7eae958504d06c1c374e630d6e0c966d62365632824aa"),
        "trigger:trg_memory_sessions_ended_at_portable_precision" to setOf("5b4595b53e6efb69232b81dba798fe457f3480887f876ab668fb69da2ecd4400"),
        "trigger:trg_memory_sessions_started_at_portable_precision" to setOf("31a6806c0e56789ecab9c5b52505e9bf474a0ee40f92c48070c7844cebefffd0"),
        "trigger:trg_message_fts_delete" to setOf("1adec5fddbea152f5feacebc764835d9b18597fe7b4aa608578a69a82730ea42"),
        "trigger:trg_message_fts_insert" to setOf("307d214a7da6d6c2fdceb9f189da37524bd1f83d01d62b171db8afe0981a08b6"),
        "trigger:trg_message_fts_update" to setOf("f81c56fd33a7db6ce29d538383509cdf13e199102c62a9cbf5e41e5ce40aa1b0"),
        "trigger:trg_tombstones_no_delete" to setOf("9a08f2cbf2bda1ac43be534b47cf74bd21a2ae27c16473bbbb3f99f95ba9337f"),
        "trigger:trg_tombstones_no_duplicate" to setOf("b574a5f02f6ac1caa795b8806482e34bde6a3e73cf8c3ee4952a0002721a976a"),
        "trigger:trg_tombstones_no_update" to setOf("0840e394a2ab4e23cc65cf22d332768fc9dc2132a581dd2272da84c1f36b017e"),
        "trigger:trg_workspace_reply_transition_monotonic" to setOf("e414fdca6d39af6971be4ae7567b592a1b00e3d2e0effb9e5a6a0a55a7a46b0d"),
        "trigger:trg_workspace_reply_update_monotonic" to setOf("05b28c93a0d28f0dab79e12bb50040ec81f5c343793e9491fb475b378b73bb33"),
        "view:message_search_content" to setOf("a22323d7b24fedf054efa18b6770b80c2a7a9f82d3c5b7f2597afa236f2631a5"),
    )
    private val tokens = Regex("""'(?:''|[^'])*'|`[^`]*`|"[^"]*"|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|[^\s]""")
    private fun digest(sql: String): String {
        val parts = tokens.findAll(sql).map { match ->
            val value = match.value
            if (value.startsWith("'")) value else value.trim('`', '"').uppercase(java.util.Locale.ROOT)
        }.toMutableList()
        val offset = if(parts.getOrNull(1) == "UNIQUE") 3 else 2
        if(parts.size >= offset+3 && parts.subList(offset,offset+3) == listOf("IF","NOT","EXISTS"))
            repeat(3) { parts.removeAt(offset) }
        val normalized = parts.joinToString(" ")
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun validate(connection: SQLiteConnection, expectedV2Identity: String, allowV1: Boolean): Int = try {
        validateChecked(connection,expectedV2Identity,allowV1)
    } catch (failure: RoomCurrentSchemaUnavailable) { throw failure
    } catch (failure: Exception) {
        throw RoomCurrentSchemaUnavailable("Current database inspection failed; data preserved",failure)
    }

    private fun validateChecked(connection: SQLiteConnection, expectedV2Identity: String, allowV1: Boolean): Int {
        val sql = DriverChatSqlDatabase(connection)
        val version = sql.query("PRAGMA user_version").use { check(it.moveToFirst()); it.getInt(0) }
        if (version != 1 && version != 2) throw RoomCurrentSchemaUnavailable("Unsupported current database version; data preserved")
        val expectedIdentity = if (version == 1) V1_IDENTITY else expectedV2Identity
        val identity = try { sql.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
            if (!it.moveToFirst()) null else it.getString(0).also { _ -> check(!it.moveToNext()) }
        } } catch (failure: Exception) { throw RoomCurrentSchemaUnavailable("Current database identity unavailable; data preserved", failure) }
        if (identity != expectedIdentity) throw RoomCurrentSchemaUnavailable("Current database identity mismatch; data preserved")
        val actual = sql.query("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger','view') AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY type,name").use { c ->
            buildMap<String,String> { while(c.moveToNext()) {
                if (c.isNull(2)) throw RoomCurrentSchemaUnavailable("Unexpected implicit database object; data preserved")
                put(c.getString(0)+":"+c.getString(1),digest(c.getString(2)))
            } }
        }
        val expected = if(version == 1) v1Objects else v1Objects + ("table:crew_message_input_provenance" to digest(CREATE_SQL))
        if(expected.any { (key,value) -> actual[key] != value })
            throw RoomCurrentSchemaUnavailable("Current database schema mismatch; data preserved")
        val shadows = sql.query("PRAGMA table_list").use { c -> buildSet<String> {
            while(c.moveToNext())if(c.getString(0)=="main" && c.getString(2)=="shadow") add(c.getString(1))
        } }
        for((key,value) in actual.filterKeys { it !in expected }) {
            if(auxiliaryIndexes[key] == value) continue
            if(auxiliaryDefinitions[key]?.contains(value)==true) continue
            val name=key.removePrefix("table:")
            if(key.startsWith("table:") && virtualTables[name]?.contains(value)==true) continue
            if(key.startsWith("table:") && name in shadows && virtualTables.any { (parent,definitions) ->
                    name.startsWith(parent+"_") && actual["table:"+parent] in definitions }) continue
            throw RoomCurrentSchemaUnavailable("Unexpected current database schema object; data preserved")
        }
        fun requireFamily(name:String, definitions:Map<String,String>) {
            val present = actual.keys.any { it == "table:"+name || it in definitions }
            if(present && definitions.any { (key,sql) -> actual[key] != digest(sql) })
                throw RoomCurrentSchemaUnavailable("Partial or mismatched native index schema; data preserved")
        }
        val memoryFive=actual["table:memory_fts"] == digest(PersonalMemoryFtsSql.FTS5_CREATE)
        if("table:memory_fts" in actual || actual.keys.any { it.startsWith("trigger:trg_memory_fts_") }) {
            requireFamily("memory_fts",mapOf(
                "table:memory_fts" to if(memoryFive) PersonalMemoryFtsSql.FTS5_CREATE else PersonalMemoryFtsSql.FTS4_CREATE,
                "trigger:trg_memory_fts_insert" to if(memoryFive) PersonalMemoryFtsSql.FTS5_INSERT_TRIGGER else PersonalMemoryFtsSql.FTS4_INSERT_TRIGGER,
                "trigger:trg_memory_fts_delete" to if(memoryFive) PersonalMemoryFtsSql.FTS5_DELETE_TRIGGER else PersonalMemoryFtsSql.FTS4_DELETE_TRIGGER,
                "trigger:trg_memory_fts_update" to if(memoryFive) PersonalMemoryFtsSql.FTS5_UPDATE_TRIGGER else PersonalMemoryFtsSql.FTS4_UPDATE_TRIGGER))
        }
        requireFamily("memory_prompts_fts",mapOf("table:memory_prompts_fts" to MemoryPromptSearchIndex.CREATE,
            "trigger:trg_memory_prompts_fts_insert" to MemoryPromptSearchIndex.INSERT,
            "trigger:trg_memory_prompts_fts_delete" to MemoryPromptSearchIndex.DELETE,
            "trigger:trg_memory_prompts_fts_update" to MemoryPromptSearchIndex.UPDATE))
        if(version == 1 && !allowV1) throw RoomCurrentSchemaUnavailable("Current database additive upgrade validation pending; data preserved")
        return version
    }
}

internal class RoomCurrentSchemaUnavailable(message: String, cause: Throwable? = null) : IllegalStateException(message,cause)

/** Room owns the atomic migration/version/identity transaction. Tests call this same operation. */
internal object RoomTreeProvenanceMigration : Migration(1,2) {
    override fun migrate(connection: SQLiteConnection) {
        check(RoomTreeProvenanceSchema.validate(connection,"unused-v2",true) == 1)
        connection.execSQL(RoomTreeProvenanceSchema.CREATE_SQL)
    }
}
